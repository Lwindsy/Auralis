"""Demand-driven supply and Java-owned continuation over the first-batch workflow."""

import asyncio
from copy import deepcopy
from datetime import datetime, timezone

from auralis_intelligence.album_reviews import AlbumDossier, ResearchResult
from auralis_intelligence.contracts import (
    ContractError,
    digest,
    validate_definition,
)
from auralis_intelligence.ranking import SelectionResult

from .pipeline import FirstBatchPipeline, RoundWork, utc_now
from .ports import CallDenied, ControlUnavailable
from .runtime import Execution


class RoundPipeline(FirstBatchPipeline):
    async def __call__(self, execution: Execution) -> None:
        execution.report_builder = self.report
        try:
            await execution.refresh_budget()
            if execution.start["start_mode"] == "CONTINUE":
                work = self.restore(execution)
                await self.finish(execution, work)
            else:
                await super().__call__(execution)
        finally:
            if not execution.cancelled.is_set():
                try:
                    await execution.flush_usage(force=True)
                    await asyncio.wait_for(
                        execution.refresh_budget(), execution.receipt_timeout_seconds
                    )
                except (ControlUnavailable, ContractError, asyncio.TimeoutError):
                    execution.lanes = execution.budget_state = None
                execution.final_report = self.report(execution)
                try:
                    await self.deliver(execution, "ROUND_REPORT", execution.final_report)
                except (ControlUnavailable, ContractError, CallDenied):
                    execution.error_code = execution.error_code or "REPORT_UNDELIVERED"

    def restore(self, execution: Execution) -> RoundWork:
        start = execution.start
        validate_definition("RoundStart", start)
        continuation = start["continuation"]
        bundle = continuation["candidate_bundle"]
        preferences = deepcopy(start["input"]["accepted_preferences"])
        candidates = {item["release_group_id"]: item for item in bundle["accepted"]}
        rows = continuation["candidates"]
        display = continuation["display_state"]
        execution.binding = deepcopy(bundle["binding"])
        execution.candidates = deepcopy(candidates)
        work = RoundWork(
            preferences,
            [
                deepcopy(candidates[row["release_group_id"]])
                for row in rows
                if row["state"] == "UNRESEARCHED"
            ],
        )
        execution.work = work
        work.shown = set(display["shown_candidate_ids"])
        work.heard = set(display["heard_candidate_ids"])
        work.ready_unshown = set(display["ready_unshown_ids"])
        work.display_seq = display["display_seq"]
        work.user_waiting = display["user_waiting"]
        for row in rows:
            identifier = row["release_group_id"]
            screening = row["screening"]
            if row["state"] in {"RESERVE", "READY", "SHOWN"} and screening is None:
                raise ContractError("CONTINUATION_SCREENING_MISSING")
            if screening is not None:
                if screening["candidate_id"] != identifier:
                    raise ContractError("BINDING_MISMATCH")
                dossier = AlbumDossier(
                    identifier,
                    deepcopy(candidates[identifier]["identity"]),
                    screening=deepcopy(screening),
                )
                execution.dossiers[identifier] = dossier
                eligible = (
                    screening["screen"]["status"] == "OK"
                    and len(screening["qualified_read_ids"]) >= 1
                    and screening["screen"]["score"]
                    >= start["agent_policy"]["screening"]["supplement_min_score"]
                )
                work.results[identifier] = ResearchResult(dossier.screening, dossier, eligible)
            if row["state"] == "READY":
                work.existing_ready.add(identifier)
            work.read_refs[identifier] = deepcopy(row["read_refs"])
            work.long_states[identifier] = (
                "INTERRUPTED"
                if row["long_review_state"] == "GENERATING"
                else row["long_review_state"]
            )
        execution.phase = "FOREGROUND" if start["foreground"] else "BACKGROUND"
        return work

    async def finish(self, execution: Execution, work: RoundWork) -> None:
        publishers: dict[str, asyncio.Task] = {}
        active: asyncio.Task | None = None
        blocked: str | None = None
        last_supply = None
        command_task = asyncio.create_task(execution.command_available.wait())

        def launch_publishers():
            for identifier, jobs in work.jobs.items():
                if (
                    jobs.long is not None
                    and identifier not in work.long_delivered
                    and identifier not in publishers
                ):
                    work.long_states[identifier] = "GENERATING"
                    publishers[identifier] = execution.spawn(
                        lambda identifier=identifier, jobs=jobs: self.publish_long(
                            execution, work, identifier, jobs.long
                        ),
                        start_work=False,
                    )

        for identifier in work.shown:
            execution.prioritize_long(identifier, 2)
        pending = (
            execution.start.get("continuation", {}).get("pending", {})
            if execution.start.get("continuation")
            else {}
        )
        if pending.get("rescreen_run_id"):
            if pending["rescreen_run_id"] != execution.start["run_id"]:
                raise ContractError("BINDING_MISMATCH")
            active = execution.spawn(
                lambda: self.rescreen(execution, work, pending["rescreen_run_id"])
            )
        for identifier in pending.get("regenerate_long_review_ids", []):
            self.regenerate(execution, work, identifier)
        for identifier in pending.get("prioritized_long_review_ids", []):
            execution.prioritize_long(identifier)
        try:
            while True:
                for identifier, task in list(publishers.items()):
                    if task.done():
                        task.result()
                        publishers.pop(identifier)
                launch_publishers()
                for identifier, task in list(work.pending_research.items()):
                    if task.done():
                        task.result()
                        work.pending_research.pop(identifier)
                if active is not None and active.done():
                    try:
                        active.result()
                    except (CallDenied, ControlUnavailable, ContractError) as error:
                        blocked = (
                            str(error)
                            if isinstance(error, (CallDenied, ContractError))
                            else "CONTROL_UNAVAILABLE"
                        )
                    active = None
                    execution.foreground = False
                    execution.phase = "BACKGROUND"
                reserve = self.reserves(execution, work)
                available = bool(reserve) or work.cursor < len(work.candidates)
                below_watermark = (
                    len(work.ready_unshown)
                    < execution.start["agent_policy"]["supply"]["replenish_watermark"]
                )
                if (
                    active is None
                    and not execution.close_requested
                    and not blocked
                    and below_watermark
                    and available
                    and (
                        work.user_waiting or not getattr(self.research, "prepared_decisions", False)
                    )
                ):
                    lane = "FOREGROUND" if work.user_waiting else "BACKGROUND"
                    execution.foreground = work.user_waiting
                    execution.phase = "FOREGROUND" if work.user_waiting else "BACKGROUND"
                    active = execution.spawn(
                        lambda lane=lane: self.supply(execution, work, lane=lane)
                    )
                working = active is not None or bool(publishers) or bool(work.pending_research)
                state = (
                    "PAUSED"
                    if blocked
                    else "SUPPLYING"
                    if active is not None
                    else "R_EXHAUSTED"
                    if not available and not working and below_watermark
                    else "SUFFICIENT"
                )
                supply = {
                    "state": state,
                    "counts": {
                        "unresearched": len(work.candidates) - work.cursor,
                        "reserve": len(reserve),
                        "in_flight": len(work.pending_research) + (1 if active else 0),
                        "ready_unshown_known": len(work.ready_unshown),
                    },
                    "reason_code": blocked,
                }
                signature = digest(supply)
                if signature != last_supply and not execution.close_requested:
                    await self.deliver(execution, "SUPPLY_STATUS", supply)
                    last_supply = signature
                if execution.close_requested and not working:
                    break
                if not working and not command_task.done() and execution.command_queue.empty():
                    execution.close_reason = execution.close_reason or (
                        "DRAINED" if not available else "IDLE"
                    )
                    execution.close_requested = True
                    break
                waiters = [command_task, *publishers.values(), *work.pending_research.values()]
                if active is not None:
                    waiters.append(active)
                done, _ = await asyncio.wait(waiters, return_when=asyncio.FIRST_COMPLETED)
                if command_task in done:
                    command = execution.command_queue.get_nowait()
                    if execution.command_queue.empty():
                        execution.command_available.clear()
                    command_task = asyncio.create_task(execution.command_available.wait())
                    kind, payload = command["kind"], command["payload"]
                    if kind == "DISPLAY_STATE":
                        if payload["display_seq"] <= work.display_seq:
                            continue
                        if set(payload["shown_candidate_ids"]) & set(
                            payload["ready_unshown_ids"]
                        ) or not set(
                            payload["shown_candidate_ids"] + payload["ready_unshown_ids"]
                        ).issubset(execution.candidates):
                            raise ContractError("BINDING_MISMATCH")
                        work.display_seq = payload["display_seq"]
                        work.shown.update(payload["shown_candidate_ids"])
                        work.heard.update(payload["heard_candidate_ids"])
                        work.ready_unshown = set(payload["ready_unshown_ids"])
                        work.user_waiting = payload["user_waiting"]
                        if work.user_waiting:
                            execution.start["run_id"] = payload["run_id"]
                            blocked = None
                        for identifier in work.shown:
                            execution.prioritize_long(identifier, 2)
                    elif kind == "RESCREEN":
                        previous = active

                        async def rerank(
                            previous=previous,
                            run_id=payload["run_id"],
                            deadline=payload["deadline"],
                        ):
                            if previous is not None:
                                await asyncio.shield(previous)
                            return await self.rescreen(execution, work, run_id, deadline=deadline)

                        active = execution.spawn(rerank)
                    elif kind == "REGENERATE_LONG_REVIEW":
                        self.regenerate(execution, work, payload["candidate_id"])
                    elif kind == "BUDGET_UPDATE":
                        state = await execution.refresh_budget()
                        execution.start["budget"]["limits"]["lane_caps"] = state["lane_caps"]
                        blocked = None
                    elif kind == "PRIORITIZE_LONG_REVIEW":
                        execution.prioritize_long(payload["candidate_id"])
                    elif kind == "CLOSE":
                        execution.close_reason = payload["reason"]
        finally:
            command_task.cancel()
            await asyncio.gather(command_task, return_exceptions=True)
            if (
                not execution.cancelled.is_set()
                and datetime.now(timezone.utc) >= execution._deadline
            ):
                execution.close_reason = "ROUND_MAX_LIFETIME"

    def reserves(self, execution, work):
        floor = execution.start["agent_policy"]["screening"]["supplement_min_score"]
        curated = execution.start["operation"] == "CURATED_REVIEWS"
        available = []
        for identifier, result in work.results.items():
            if identifier in work.shown | work.heard | work.existing_ready | set(work.cards):
                continue
            jobs = work.jobs.get(identifier)
            if jobs is not None:
                if (
                    not jobs.short.done()
                    or jobs.short.cancelled()
                    or jobs.short.exception() is not None
                ):
                    continue
                short = jobs.short.result()
                if short is None or short.status not in {"READY", "BUDGET_EXHAUSTED"}:
                    continue
            if (
                result.screening["screen"]["status"] == "OK"
                and len(result.screening["qualified_read_ids"]) >= 1
                and (curated or result.screening["screen"]["score"] >= floor)
                and result.screening["disposition"] in {"RESERVE", "SHORTLISTED", "BELOW_FLOOR"}
            ):
                available.append(result)
        return sorted(
            available,
            key=lambda result: (
                (result.screening["recall_rank"],)
                if curated
                else (-result.screening["screen"]["score"], result.screening["recall_rank"])
            ),
        )

    async def hydrate(
        self, execution, work, identifier, *, qualification_only=False, lane="BACKGROUND"
    ):
        old = work.results.get(identifier)
        if (
            old is not None
            and len(old.dossier.qualified) >= 1
            and all(record.excerpt for record in old.dossier.qualified)
        ):
            eligible = (
                qualification_only
                or execution.start["operation"] == "CURATED_REVIEWS"
                or old.screening["screen"]["score"]
                >= execution.start["agent_policy"]["screening"]["supplement_min_score"]
            )
            if eligible:
                old.screening.update(disposition="RESERVE", reason_code=None)
            return ResearchResult(old.screening, old.dossier, eligible)
        result = await self.research.run(
            execution,
            execution.candidates[identifier],
            work.preferences,
            lane=lane,
            known_read_refs=work.read_refs.get(identifier),
            require_score=not qualification_only,
            min_score=execution.start["agent_policy"]["screening"]["supplement_min_score"],
        )
        work.results[identifier] = result
        await self.deliver(execution, "SCREENINGS", [result.screening])
        return result

    async def supply(self, execution, work, *, lane, publish=True):
        if lane == "FOREGROUND" and publish:
            work.agent_t0 = utc_now()
            work.frozen_ms = work.in_flight_at_freeze = 0
            execution.clock.start_agent(reset_phases=True)
        count = execution.start["agent_policy"]["supply"]["supply_batch_size"]
        if getattr(self.research, "prepared_decisions", False):
            fresh = await self.research.next_batch(execution, work)
            work.results.update(fresh)
            chosen = [fresh[i] for i in self.research.order]
            work.cursor = (
                0
                if any(r.screening["disposition"] == "DEFERRED" for r in fresh.values())
                else len(work.candidates)
            )
            await self.deliver(execution, "SCREENINGS", [r.screening for r in fresh.values()])
        else:
            chosen = self.reserves(execution, work)[:count]
        while (
            len(chosen) < count
            and work.cursor < len(work.candidates)
            and not getattr(self.research, "prepared_decisions", False)
        ):
            execution.ensure_active()
            candidate = work.candidates[work.cursor]
            work.cursor += 1
            if candidate["release_group_id"] in work.heard | work.shown:
                continue
            result = await self.research_one(
                execution,
                work,
                candidate,
                lane=lane,
                min_score=execution.start["agent_policy"]["screening"]["supplement_min_score"],
            )
            if result.eligible and result.dossier.candidate_id not in work.shown | work.heard:
                chosen.append(result)
        hydrated = []
        for result in chosen:
            result = await self.hydrate(execution, work, result.dossier.candidate_id, lane=lane)
            if result.eligible:
                hydrated.append(result)
        if not hydrated:
            return
        for result in hydrated:
            result.screening["disposition"] = "SHORTLISTED"
            previous = work.jobs.get(result.dossier.candidate_id)
            if previous is None and not self.generate_long_reviews:
                from auralis_intelligence.album_reviews.writing import WritingJobs

                work.jobs[result.dossier.candidate_id] = WritingJobs(
                    execution.spawn(
                        lambda result=result: self.writing.short(
                            execution, result.dossier, work.preferences, lane=lane
                        )
                    ),
                    None,
                )
            elif previous is None:
                if (
                    work.long_states.get(result.dossier.candidate_id, "NOT_STARTED")
                    != "NOT_STARTED"
                ):
                    from auralis_intelligence.album_reviews.writing import WritingJobs

                    known_long = asyncio.get_running_loop().create_future()
                    known_long.set_result(None)
                    work.long_delivered.add(result.dossier.candidate_id)
                    work.jobs[result.dossier.candidate_id] = WritingJobs(
                        execution.spawn(
                            lambda result=result: self.writing.short(
                                execution, result.dossier, work.preferences, lane=lane
                            )
                        ),
                        known_long,
                    )
                else:
                    work.jobs[result.dossier.candidate_id] = self.writing.start(
                        execution, result.dossier, work.preferences, lane=lane
                    )
                    work.long_states[result.dossier.candidate_id] = "GENERATING"
            else:
                from auralis_intelligence.album_reviews.writing import WritingJobs

                short = (
                    previous.short
                    if previous.short.done() and previous.short.result().status == "READY"
                    else execution.spawn(
                        lambda result=result: self.writing.short(
                            execution, result.dossier, work.preferences, lane=lane
                        )
                    )
                )
                work.jobs[result.dossier.candidate_id] = WritingJobs(short, previous.long)
        curated = execution.start["operation"] == "CURATED_REVIEWS"
        selection_task = (
            None
            if curated
            else execution.spawn(
                lambda: self.selection.run(
                    execution,
                    [result.screening for result in hydrated],
                    work.preferences,
                    shown_ids=work.shown | work.heard,
                    lane=lane,
                )
            )
        )
        shorts = await asyncio.gather(
            *(work.jobs[result.dossier.candidate_id].short for result in hydrated)
        )
        selection = (
            SelectionResult(
                {"status": "NOT_RUN", "order": [], "invocation_id": None},
                "CURATED",
                [result.dossier.candidate_id for result in hydrated],
                {},
            )
            if curated
            else await selection_task
        )
        rejected = {
            item["candidate_id"]
            for item in selection.selection["order"]
            if item["verdict"] == "reject"
        }
        cards = []
        for result, short in zip(hydrated, shorts):
            identifier = result.dossier.candidate_id
            if short.status != "READY" or identifier in rejected:
                result.screening.update(
                    disposition="RESERVE" if short.status == "BUDGET_EXHAUSTED" else "INELIGIBLE",
                    reason_code=None
                    if short.status == "BUDGET_EXHAUSTED"
                    else "SELECT_REJECTED"
                    if identifier in rejected
                    else "WRITE_FAILED",
                )
                continue
            card = {
                "candidate_id": identifier,
                "selection_basis": "CURATED"
                if curated
                else "SELECTED"
                if selection.analysis_mode == "PERSONALIZED"
                else "SCREEN_ONLY",
                "short_review": short.review,
                "admission": {
                    "qualified_read_ids": result.screening["qualified_read_ids"],
                    "screen_score": result.screening["screen"]["score"],
                    "screen_invocation_id": result.screening["screen"]["invocation_id"],
                },
                "long_review_state": work.long_states.get(
                    identifier, "GENERATING" if self.generate_long_reviews else "NOT_STARTED"
                ),
            }
            audience = execution.candidates[identifier].get("audience")
            if audience is not None:
                card["admission"]["audience"] = audience
            cards.append(card)
            work.cards[identifier] = card
        if not cards and any(short.status == "BUDGET_EXHAUSTED" for short in shorts):
            raise CallDenied("BACKGROUND_BUDGET_EXHAUSTED")
        payload = {
            "batch_kind": "SUPPLY",
            "outcome": "DISPLAYABLE" if cards else "FAILED",
            "reason_code": None if cards else "WRITE_FAILED",
            "input_issues": [],
            "analysis_mode": selection.analysis_mode,
            "selection": selection.selection,
            "screen_order": selection.screen_order,
            "cards": cards,
            "screenings": [result.screening for result in hydrated],
            "timing": self.timing(execution, work, len(hydrated), len(cards))
            if lane == "FOREGROUND" and publish
            else None,
            "pending": {
                "long_reviews_generating": sum(
                    jobs.long is not None and not jobs.long.done() for jobs in work.jobs.values()
                ),
                "deferred_candidates": len(work.candidates) - work.cursor,
                "supply_state": "SUPPLYING",
            },
        }
        receipt = await self.deliver(
            execution,
            "CARD_BATCH",
            payload,
            run_id=execution.start["run_id"] if lane == "FOREGROUND" and publish else None,
        )
        self.display_result(work, receipt)
        if curated:
            return
        order = (
            selection.selection["order"]
            if selection.analysis_mode == "PERSONALIZED"
            else [
                {"candidate_id": identifier, "verdict": "recommend"}
                for identifier in selection.screen_order
            ]
        )
        order = [item for item in order if item["candidate_id"] in work.ready_unshown]
        update = {
            "basis_display_seq": work.display_seq,
            "run_id": None,
            "analysis_mode": selection.analysis_mode,
            "selection": selection.selection,
            "order": order,
            "timing": None,
        }
        self.display_result(work, await self.deliver(execution, "ORDER_UPDATE", update))
        if any(short.status == "BUDGET_EXHAUSTED" for short in shorts):
            raise CallDenied("BACKGROUND_BUDGET_EXHAUSTED")

    async def rescreen(self, execution, work, run_id, *, deadline=None):
        execution.foreground = True
        execution.phase = "FOREGROUND"
        execution.start["run_id"] = run_id
        work.agent_t0 = utc_now()
        work.frozen_ms = work.in_flight_at_freeze = 0
        execution.clock.start_agent(reset_phases=True)
        work.user_waiting = True
        try:
            if deadline is None:
                return await self._rescreen_work(execution, work, run_id)
            seconds = max(
                0,
                (
                    datetime.fromisoformat(deadline.replace("Z", "+00:00"))
                    - datetime.now(timezone.utc)
                ).total_seconds(),
            )
            return await asyncio.wait_for(self._rescreen_work(execution, work, run_id), seconds)
        except asyncio.TimeoutError:
            order = sorted(
                (
                    work.results[identifier]
                    for identifier in work.ready_unshown
                    if identifier in work.results
                ),
                key=lambda result: (
                    -result.screening["screen"]["score"],
                    result.screening["recall_rank"],
                ),
            )
            selection = SelectionResult(
                {"status": "TIMED_OUT", "order": [], "invocation_id": None},
                "BASIC",
                [result.dossier.candidate_id for result in order],
                {},
                "SELECT_TIMED_OUT",
            )
            await self.order_update(execution, work, selection, run_id)
        finally:
            execution.foreground = False
            execution.phase = "BACKGROUND"

    async def _rescreen_work(self, execution, work, run_id):
        if (
            len(work.ready_unshown)
            < execution.start["selection_config"]["recommendation_target_count"]
        ):
            try:
                await self.supply(execution, work, lane="FOREGROUND", publish=False)
            except CallDenied:
                pass
        screenings = [
            work.results[identifier].screening
            for identifier in work.ready_unshown
            if identifier in work.results
        ]
        selection = await self.selection.run(
            execution, screenings, work.preferences, shown_ids=work.shown | work.heard
        )
        await self.order_update(execution, work, selection, run_id)

    async def order_update(self, execution, work, selection, run_id):
        for attempt in range(2):
            order = (
                selection.selection["order"]
                if selection.analysis_mode == "PERSONALIZED"
                else [
                    {"candidate_id": identifier, "verdict": "recommend"}
                    for identifier in selection.screen_order
                ]
            )
            order = [
                item
                for item in order
                if item["candidate_id"] in work.ready_unshown
                and item["candidate_id"] not in work.shown | work.heard
            ]
            basis = work.display_seq
            payload = {
                "basis_display_seq": basis,
                "run_id": run_id,
                "analysis_mode": selection.analysis_mode,
                "selection": selection.selection,
                "order": order,
                "timing": self.timing(execution, work, 0, 0),
            }
            try:
                receipt = await self.deliver(execution, "ORDER_UPDATE", payload, run_id=run_id)
                self.display_result(work, receipt)
                return
            except ContractError as error:
                if str(error) != "BINDING_MISMATCH" or attempt:
                    raise
                await asyncio.sleep(0.01)
                if work.display_seq <= basis:
                    raise

    def regenerate(self, execution, work, identifier):
        if identifier not in work.shown | work.existing_ready | set(work.cards):
            raise ContractError("LONG_REVIEW_NOT_AVAILABLE")
        jobs = work.jobs.get(identifier)
        if jobs is not None and jobs.long is not None and not jobs.long.done():
            execution.prioritize_long(identifier)
            return
        if work.long_states.get(identifier) == "READY":
            return
        if work.long_states.get(identifier) == "GENERATING":
            execution.prioritize_long(identifier)
            return
        execution.prioritize_long(identifier)

        async def regenerate():
            result = await self.hydrate(execution, work, identifier, qualification_only=True)
            if not result.eligible:
                return {
                    "candidate_id": identifier,
                    "status": "NEEDS_MORE_RESEARCH",
                    "sections": None,
                    "citations": [],
                    "han_char_count": 0,
                    "extra_read_ids": [],
                    "missing_sections": ["production", "instrumentation", "vocals", "mood"],
                    "invocation_ids": [],
                    "prompt_version": "long_review.v1.1",
                    "reason_code": "REVIEW_MATERIAL_INSUFFICIENT",
                }
            return await self.writing.long(execution, result.dossier, work.preferences, priority=1)

        from auralis_intelligence.album_reviews.writing import WritingJobs

        completed_short = asyncio.get_running_loop().create_future()
        completed_short.set_result(None)
        work.long_delivered.discard(identifier)
        work.long_states[identifier] = "GENERATING"
        work.jobs[identifier] = WritingJobs(completed_short, execution.spawn(regenerate))

    async def publish_long(self, execution, work, identifier, task):
        result = await asyncio.shield(task)
        receipt = await self.deliver(execution, "LONG_REVIEW", result)
        accepted = not any(item["decision"] == "REJECTED" for item in receipt["items"])
        work.long_states[identifier] = result["status"] if accepted else "FAILED"
        if not accepted:
            result.update(
                status="FAILED", sections=None, citations=[], reason_code="PUBLICATION_REJECTED"
            )
            if identifier in work.results:
                work.results[identifier].dossier.clear()
        work.long_delivered.add(identifier)
        execution.counters["long_ready"] += accepted and result["status"] == "READY"

    @staticmethod
    def display_result(work, receipt):
        display = receipt["display_state"]
        if display["display_seq"] >= work.display_seq:
            work.display_seq = display["display_seq"]
            work.shown.update(display["shown_candidate_ids"])
            work.heard.update(display["heard_candidate_ids"])
            work.ready_unshown = set(display["ready_unshown_ids"])
            work.user_waiting = display["user_waiting"]

    def report(self, execution):
        work = execution.work
        results = list(work.results.values()) if work else []
        reason = execution.close_reason
        close = (
            "CANCELLED"
            if execution.cancelled.is_set()
            else reason
            if reason in {"IDLE", "DRAINED", "ROUND_MAX_LIFETIME"}
            else "CLOSE_COMMAND"
            if execution.close_requested and reason
            else "FAILED"
            if execution.error_code or execution.first_outcome in {"FAILED", "NEEDS_INPUT"}
            else "DRAINED"
        )
        state = execution.budget_state
        report = {
            "close_reason": close,
            "trigger_reason": reason or execution.first_reason or execution.handoff,
            "counts": {
                "researched": len(results),
                "qualified": sum(
                    result.screening["screen"]["status"] == "OK"
                    and len(result.screening["qualified_read_ids"]) >= 1
                    for result in results
                ),
                "shortlisted": len((set(work.cards) | work.existing_ready | work.shown))
                if work
                else 0,
                "ready": len(work.ready_unshown) if work else 0,
                "long_ready": sum(value == "READY" for value in work.long_states.values())
                if work
                else 0,
                "ineligible": sum(
                    result.screening["disposition"] == "INELIGIBLE" for result in results
                ),
                "below_floor": sum(
                    result.screening["disposition"] == "BELOW_FLOOR" for result in results
                ),
                "unresearched": len(work.candidates) - work.cursor if work else 0,
                "reserve": len(self.reserves(execution, work)) if work else 0,
                "shown": len(work.shown) if work else 0,
            },
            "elapsed_ms": max(0, round((execution.clock.now() - execution.created_at) * 1000)),
            "lanes": deepcopy(execution.lanes),
            "structure_repairs": state["structure_repairs"] if state else None,
            "cached_input_tokens": state["cached_input_tokens"] if state else None,
            "input_tokens": state["input_tokens"] if state else None,
        }
        validate_definition("RoundReport", report)
        return report
