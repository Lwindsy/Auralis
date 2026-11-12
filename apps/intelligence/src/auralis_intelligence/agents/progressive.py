"""Publish each accepted short review as its candidate finishes."""

import asyncio

from auralis_intelligence.album_reviews import AlbumDossier

from .background import RoundPipeline

CANDIDATE_TIMEOUT_SECONDS = 20


class ProgressivePipeline(RoundPipeline):
    async def first_batch(self, execution):
        work = await self.prepare_work(execution)
        if work is not None:
            await self.publish_candidates(execution, work)
        return work

    async def finish(self, execution, work):
        if execution.start["start_mode"] != "CONTINUE":
            return
        if execution.start["run_kind"] != "SUPPLY":
            await super().finish(execution, work)
            return
        candidates = {c["release_group_id"]: c for c in work.candidates}
        for identifier, result in work.results.items():
            if result.eligible and identifier not in work.shown | work.heard | work.existing_ready:
                candidates[identifier] = execution.candidates[identifier]
        work.candidates = sorted(candidates.values(), key=lambda c: c["recall_rank"])
        await self.publish_candidates(execution, work)

    async def candidate(self, execution, work, candidate):
        identifier = candidate["release_group_id"]
        try:
            result = await asyncio.wait_for(
                self.research.run(
                    execution,
                    candidate,
                    work.preferences,
                    known_read_refs=work.read_refs.get(identifier),
                ),
                CANDIDATE_TIMEOUT_SECONDS,
            )
        except asyncio.TimeoutError:
            dossier = execution.dossiers.get(identifier) or AlbumDossier(
                identifier, candidate["identity"]
            )
            result = self.research._failed(candidate, dossier, "READ_FAILED", "CANDIDATE_TIMEOUT")
        work.results[identifier] = result
        await self.deliver(execution, "SCREENINGS", [result.screening])
        if not self.eligible_now(result):
            return None
        jobs = self.writing.start(execution, result.dossier, work.preferences)
        work.jobs[identifier] = jobs
        short = await jobs.short
        if short.status != "READY":
            result.screening.update(disposition="INELIGIBLE", reason_code="WRITE_FAILED")
            await self.deliver(execution, "SCREENINGS", [result.screening])
            return None
        card = {
            "candidate_id": identifier,
            "selection_basis": "INDIVIDUAL",
            "short_review": short.review,
            "admission": {
                "qualified_read_ids": result.screening["qualified_read_ids"],
                "screen_score": result.screening["screen"]["score"],
                "screen_invocation_id": result.screening["screen"]["invocation_id"],
                "audience": candidate["audience"],
            },
            "long_review_state": "GENERATING",
        }
        return card

    async def publish_candidates(self, execution, work):
        config = execution.start["selection_config"]
        width = config["shortlist_target_count"]
        pool = [c for c in work.candidates if c["release_group_id"] not in work.shown | work.heard]
        tasks = {}
        publishers = []
        finished = asyncio.Queue()
        tried = 0

        def start_next():
            nonlocal tried
            while tried < len(pool):
                candidate = pool[tried]
                tried += 1
                identifier = candidate["release_group_id"]
                if identifier in work.heard or identifier in work.shown:
                    continue
                task = execution.spawn(lambda: self.candidate(execution, work, candidate))
                tasks[task] = identifier
                task.add_done_callback(finished.put_nowait)
                break

        def running_count():
            return sum(not task.done() for task in tasks)

        def fill_capacity():
            for _ in range(width - running_count()):
                start_next()

        async def receive_tail_demand():
            seen_tails = set()
            while True:
                await execution.command_available.wait()
                while not execution.command_queue.empty():
                    command = execution.command_queue.get_nowait()
                    if command["kind"] != "DISPLAY_STATE":
                        continue
                    payload = command["payload"]
                    work.heard.update(payload["heard_candidate_ids"])
                    demand = payload.get("progressive_tail") or {}
                    tail = demand.get("candidate_id")
                    if (
                        tail
                        and demand.get("run_id") == execution.start["run_id"]
                        and tail not in seen_tails
                        and not execution.close_requested
                    ):
                        seen_tails.add(tail)
                        fill_capacity()
                execution.command_available.clear()

        listener = asyncio.create_task(receive_tail_demand())
        try:
            fill_capacity()
            while tasks:
                done = await finished.get()
                identifier = tasks.pop(done)
                card = done.result()
                accepted = False
                if card is not None:
                    receipt = await self.publish_progress(
                        execution, work, [card], "SUPPLYING", len(pool) - tried
                    )
                    if identifier in receipt["published_candidate_ids"]:
                        accepted = True
                        work.cards[identifier] = card
                        work.long_states[identifier] = "GENERATING"
                        execution.counters["ready"] += 1
                        execution.prioritize_long(identifier, 2)
                        long = work.jobs[identifier].long
                        publishers.append(
                            execution.spawn(
                                lambda identifier=identifier, long=long: self.publish_long(
                                    execution, work, identifier, long
                                ),
                                start_work=False,
                            )
                        )
                    else:
                        result = work.results[identifier]
                        result.screening.update(
                            disposition="INELIGIBLE", reason_code="WRITE_FAILED"
                        )
                        await self.deliver(execution, "SCREENINGS", [result.screening])
                if not accepted and running_count() < width and not execution.close_requested:
                    start_next()
            listener.cancel()
            await asyncio.gather(listener, return_exceptions=True)
            state = "SUFFICIENT" if tried < len(pool) else "R_EXHAUSTED"
            await self.publish_progress(execution, work, [], state, len(pool) - tried)
        finally:
            listener.cancel()
            for task in tasks:
                task.cancel()
            await asyncio.gather(listener, *tasks, return_exceptions=True)
        # Even an unpublishable short can have a long call that already needs settlement.
        unpublished = [
            jobs.long
            for identifier, jobs in work.jobs.items()
            if identifier not in work.cards and jobs.long is not None
        ]
        execution.foreground = False
        execution.phase = "BACKGROUND"
        await asyncio.gather(*publishers, *unpublished)

    async def publish_progress(self, execution, work, cards, state, remaining):
        displayable = bool(cards or work.cards)
        technical_failure = any(
            result.error_code
            or result.screening["reason_code"] in {"SCREEN_FAILED", "WRITE_FAILED"}
            for result in work.results.values()
        )
        outcome = "DISPLAYABLE" if displayable else "FAILED" if technical_failure else "NO_MATCH"
        payload = {
            "batch_kind": "PROGRESSIVE",
            "outcome": outcome,
            "reason_code": None
            if displayable
            else "WRITE_FAILED"
            if technical_failure
            else "REVIEW_COVERAGE_INSUFFICIENT",
            "input_issues": [],
            "analysis_mode": "PERSONALIZED",
            "selection": {"status": "NOT_RUN", "order": [], "invocation_id": None},
            "screen_order": [card["candidate_id"] for card in cards],
            "cards": cards,
            "screenings": [],
            "timing": None,
            "pending": {
                "long_reviews_generating": sum(
                    value == "GENERATING" for value in work.long_states.values()
                ),
                "deferred_candidates": remaining,
                "supply_state": state,
            },
        }
        work.first_payload = payload
        execution.first_outcome = outcome
        receipt = await self.deliver(
            execution, "CARD_BATCH", payload, run_id=execution.start["run_id"]
        )
        self.display_result(work, receipt)
        return receipt
