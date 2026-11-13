"""First-batch composition over stage ports; independent of HTTP and providers."""

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from auralisy_intelligence.album_reviews import ResearchResult, ResearchStage, WriteStage
from auralisy_intelligence.album_reviews.writing import WritingJobs
from auralisy_intelligence.contracts import ContractError
from auralisy_intelligence.preferences import PreferenceError, PreferencesStage
from auralisy_intelligence.ranking import SelectStage

from .ports import CallDenied, ControlUnavailable, DeliveryPending
from .recall_stage import RecallStage
from .runtime import Execution


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@dataclass
class RoundWork:
    preferences: dict[str, Any]
    candidates: list[dict[str, Any]]
    results: dict[str, ResearchResult] = field(default_factory=dict)
    jobs: dict[str, Any] = field(default_factory=dict)
    cards: dict[str, dict[str, Any]] = field(default_factory=dict)
    pending_research: dict[str, asyncio.Task] = field(default_factory=dict)
    long_delivered: set[str] = field(default_factory=set)
    shown: set[str] = field(default_factory=set)
    cursor: int = 0
    agent_t0: str | None = None
    frozen_ms: int = 0
    in_flight_at_freeze: int = 0
    first_payload: dict[str, Any] | None = None
    display_seq: int = 0
    ready_unshown: set[str] = field(default_factory=set)
    existing_ready: set[str] = field(default_factory=set)
    read_refs: dict[str, list] = field(default_factory=dict)
    long_states: dict[str, str] = field(default_factory=dict)
    user_waiting: bool = False
    heard: set[str] = field(default_factory=set)

    def clear(self) -> None:
        self.preferences.clear()
        self.candidates.clear()
        self.results.clear()
        self.jobs.clear()
        self.cards.clear()
        self.pending_research.clear()
        self.long_delivered.clear()
        self.shown.clear()
        self.ready_unshown.clear()
        self.existing_ready.clear()
        self.read_refs.clear()
        self.long_states.clear()
        self.heard.clear()
        self.first_payload = None


class FirstBatchPipeline:
    def __init__(
        self,
        preferences: PreferencesStage,
        recall: RecallStage,
        research: ResearchStage,
        writing: WriteStage,
        selection: SelectStage,
        *,
        generate_long_reviews: bool = True,
    ) -> None:
        self.preferences = preferences
        self.recall = recall
        self.research = research
        self.writing = writing
        self.selection = selection
        self.generate_long_reviews = generate_long_reviews

    @staticmethod
    def eligible_now(result: ResearchResult) -> bool:
        return (
            result.eligible
            and len(result.dossier.qualified) >= 1
            and all(record.excerpt for record in result.dossier.qualified)
        )

    async def deliver(
        self, execution: Execution, kind: str, payload: Any, *, run_id: str | None = None
    ) -> dict[str, Any]:
        try:
            return await execution.deliver(kind, payload, run_id=run_id)
        except DeliveryPending as error:
            for delay in (0.02, 0.05):
                await asyncio.sleep(delay)
                try:
                    return await execution._send_delivery(error.delivery_id)
                except ControlUnavailable:
                    continue
            execution.error_code = "CONTROL_UNAVAILABLE"
            raise

    async def __call__(self, execution: Execution) -> None:
        try:
            work = await self.first_batch(execution)
            if work is not None:
                await self.finish(execution, work)
        except PreferenceError as error:
            raw = execution.start["input"].get("raw")
            text = raw["request_text"] if raw else ""
            spans = error.spans or ({"start": 0, "end": max(1, len(text))},)
            issues = (
                [
                    {
                        "code": error.code,
                        "text_span": span,
                        "message_key": "input." + error.code.lower(),
                    }
                    for span in spans
                ]
                if error.status == "NEEDS_INPUT"
                else []
            )
            await self.fail(
                execution,
                error.status,
                "INTERPRETATION_FAILED" if error.status == "FAILED" else error.code,
                issues=issues,
            )
        except (CallDenied, ContractError) as error:
            if execution.work is not None and execution.work.first_payload is not None:
                execution.error_code = str(error)
                raise
            code = str(error)
            reason = (
                "BUDGET_EXHAUSTED"
                if any(value in code for value in ("BUDGET", "LANE_EXHAUSTED", "CALL_LIMIT"))
                else code
            )
            await self.fail(execution, "FAILED", reason)
        except ControlUnavailable:
            execution.error_code = "CONTROL_UNAVAILABLE"
            if execution.work is not None and execution.work.first_payload is not None:
                raise
            await self.fail(execution, "FAILED", "CONTROL_UNAVAILABLE")

    async def fail(self, execution, outcome, reason, *, issues=None, work=None):
        execution.first_outcome, execution.first_reason = outcome, reason
        payload = {
            "batch_kind": "FIRST",
            "outcome": outcome,
            "reason_code": reason,
            "input_issues": issues or [],
            "analysis_mode": "BASIC",
            "selection": {"status": "NOT_RUN", "order": [], "invocation_id": None},
            "screen_order": [],
            "cards": [],
            "screenings": [],
            "timing": None,
            "pending": {
                "long_reviews_generating": 0,
                "deferred_candidates": 0,
                "supply_state": "R_EXHAUSTED",
            },
        }
        if work is not None and work.agent_t0 is not None:
            payload["timing"] = self.timing(execution, work, 0, 0)
            work.first_payload = payload
        await self.deliver(execution, "CARD_BATCH", payload, run_id=execution.start["run_id"])

    async def prepare_work(self, execution: Execution) -> RoundWork | None:
        if execution.start["start_mode"] == "CONTINUE":
            raise ContractError("CONTINUATION_NOT_ASSEMBLED")
        preferences = await self.preferences(execution)
        if not preferences["has_positive_signal"]:
            # Java already received/accepted this snapshot and owns the fixed-list branch.
            execution.handoff = "JAVA_CURATED"
            execution.phase = "IDLE"
            return None
        else:
            proposal = await self.recall(execution, preferences)
            if proposal["execution_status"] not in {"COMPLETE", "NO_COVERAGE"}:
                await self.fail(execution, "FAILED", "RECALL_UNAVAILABLE")
                return None
            with execution.clock.measure("candidate_validation"):
                bundle = await execution.control_request("candidate-validation", proposal)
        work = RoundWork(preferences, list(bundle["accepted"]))
        execution.work = work
        work.agent_t0 = utc_now()
        execution.clock.start_agent()
        execution.phase = "FOREGROUND"
        return work

    async def first_batch(self, execution: Execution) -> RoundWork | None:
        work = await self.prepare_work(execution)
        if work is None:
            return None
        preferences = work.preferences
        if not work.candidates:
            await self.fail(execution, "NO_MATCH", "RECALL_NO_COVERAGE", work=work)
            return work
        execution.start["agent_policy"]
        target = execution.start["selection_config"]["shortlist_target_count"]
        # All recalled candidates receive a research opportunity; no speed-biased freeze.
        # All candidate tasks advance independently; scheduler limits actual I/O resources.
        tasks = [
            execution.spawn(
                lambda candidate=candidate: self.research_one(execution, work, candidate)
            )
            for candidate in work.candidates
        ]
        try:
            await asyncio.gather(*tasks)
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        work.cursor = (
            0
            if getattr(self.research, "prepared_decisions", False)
            and any(r.screening["disposition"] == "DEFERRED" for r in work.results.values())
            else len(work.candidates)
        )
        eligible = [result for result in work.results.values() if self.eligible_now(result)]
        eligible.sort(
            key=lambda result: (
                -result.screening["screen"]["score"],
                result.screening["recall_rank"],
            )
        )
        shortlist = eligible[:target]
        work.frozen_ms = round((execution.clock.now() - execution.clock.agent_start) * 1000)
        work.in_flight_at_freeze = len(work.pending_research)
        if not shortlist:
            reasons = [result.screening["reason_code"] for result in work.results.values()]
            fault = next(
                (
                    result.error_code or result.screening["reason_code"]
                    for result in work.results.values()
                    if result.error_code
                    or result.screening["reason_code"] in {"READ_FAILED", "SCREEN_FAILED"}
                ),
                None,
            )
            if fault is None and "EVIDENCE_NOT_PREPARED" in reasons:
                fault = "EVIDENCE_NOT_PREPARED"
            await self.fail(
                execution,
                "FAILED" if fault else "NO_MATCH",
                fault
                or ("BELOW_FLOOR" if "BELOW_FLOOR" in reasons else "REVIEW_COVERAGE_INSUFFICIENT"),
                work=work,
            )
            return work
        execution.counters["shortlisted"] = len(shortlist)
        for result in shortlist:
            result.screening["disposition"] = "SHORTLISTED"
            work.jobs[result.dossier.candidate_id] = (
                self.writing.start(execution, result.dossier, preferences)
                if self.generate_long_reviews
                else WritingJobs(
                    execution.spawn(
                        lambda dossier=result.dossier: self.writing.short(
                            execution, dossier, preferences
                        )
                    ),
                    None,
                )
            )
        selection_task = execution.spawn(
            lambda: self.selection.run(
                execution, [result.screening for result in shortlist], preferences
            )
        )
        short_outputs = await asyncio.gather(
            *(work.jobs[result.dossier.candidate_id].short for result in shortlist)
        )
        selected = await selection_task
        cards = []
        rejected_ids = {
            item["candidate_id"]
            for item in selected.selection["order"]
            if item["verdict"] == "reject"
        }
        for result, short in zip(shortlist, short_outputs):
            if short.status != "READY" or result.dossier.candidate_id in rejected_ids:
                result.screening.update(
                    disposition="INELIGIBLE",
                    reason_code="SELECT_REJECTED"
                    if result.dossier.candidate_id in rejected_ids
                    else "WRITE_FAILED",
                )
                continue
            card = {
                "candidate_id": result.dossier.candidate_id,
                "selection_basis": "SELECTED"
                if selected.analysis_mode == "PERSONALIZED"
                else "SCREEN_ONLY",
                "short_review": short.review,
                "admission": {
                    "qualified_read_ids": result.screening["qualified_read_ids"],
                    "screen_score": result.screening["screen"]["score"],
                    "screen_invocation_id": result.screening["screen"]["invocation_id"],
                },
                "long_review_state": "GENERATING" if self.generate_long_reviews else "NOT_STARTED",
            }
            audience = execution.candidates[card["candidate_id"]].get("audience")
            if audience is not None:
                card["admission"]["audience"] = audience
            cards.append(card)
            work.cards[card["candidate_id"]] = card
        execution.counters["ready"] = len(cards)
        payload = {
            "batch_kind": "FIRST",
            "outcome": "DISPLAYABLE" if cards else "FAILED",
            "reason_code": None if cards else "WRITE_FAILED",
            "input_issues": [],
            "analysis_mode": selected.analysis_mode,
            "selection": selected.selection,
            "screen_order": selected.screen_order,
            "cards": cards,
            "screenings": [result.screening for result in shortlist],
            "timing": self.timing(execution, work, len(shortlist), len(cards)),
            "pending": {
                "long_reviews_generating": sum(
                    job.long is not None and not job.long.done() for job in work.jobs.values()
                ),
                "deferred_candidates": sum(
                    r.screening["disposition"] == "DEFERRED" for r in work.results.values()
                )
                if getattr(self.research, "prepared_decisions", False)
                else len(work.candidates) - len(shortlist),
                "supply_state": "SUFFICIENT",
            },
        }
        work.first_payload = payload
        execution.first_outcome = payload["outcome"]
        execution.first_reason = payload["reason_code"]
        receipt = await self.deliver(
            execution, "CARD_BATCH", payload, run_id=execution.start["run_id"]
        )
        work.shown.update(receipt["display_state"]["shown_candidate_ids"])
        work.ready_unshown = set(receipt["display_state"]["ready_unshown_ids"])
        work.display_seq = receipt["display_state"]["display_seq"]
        execution.foreground = False
        execution.phase = "BACKGROUND"
        return work

    async def research_one(self, execution, work, candidate, *, lane="FOREGROUND", min_score=None):
        result = await self.research.run(
            execution, candidate, work.preferences, lane=lane, min_score=min_score
        )
        work.results[candidate["release_group_id"]] = result
        await self.deliver(execution, "SCREENINGS", [result.screening])
        return result

    def timing(self, execution, work, shortlist_count, card_count):
        times = execution.clock.finish_agent()
        recent = [
            call
            for call in execution.calls.values()
            if call["queued_at"] >= execution.clock.agent_start
        ]
        short_calls = [call for call in recent if call["purpose"] == "WRITE_SHORT"]
        long_calls = [call for call in recent if call["purpose"] == "WRITE_LONG"]
        select_calls = [call for call in recent if call["purpose"] == "SELECT"]

        def durations(calls):
            return [
                round((call["finished_at"] - call["sent_at"]) * 1000)
                for call in calls
                if call["finished_at"] is not None and call["sent_at"] is not None
            ]

        delays = []
        for call in long_calls:
            short = next(
                (item for item in short_calls if item["candidate_id"] == call["candidate_id"]), None
            )
            if short is not None and short["sent_at"] is not None and call["sent_at"] is not None:
                delays.append(max(0, round((call["sent_at"] - short["sent_at"]) * 1000)))
        return {
            "agent_t0_at": work.agent_t0,
            "agent_t1_at": utc_now(),
            **times,
            **(
                {
                    "batch_ms": sum(
                        durations(
                            [
                                c
                                for c in recent
                                if c["lane"] == "FOREGROUND"
                                and c["purpose"] in {"SELECT", "STRUCTURE_REPAIR"}
                            ]
                        )
                    )
                }
                if getattr(self.research, "prepared_decisions", False)
                else {}
            ),
            "shortlist_frozen_ms": work.frozen_ms,
            "select_ms": max(durations(select_calls), default=0),
            "short_review_max_ms": max(durations(short_calls), default=0),
            "long_dispatch_delay_ms": max(delays, default=0)
            if len(delays) == shortlist_count
            else None,
            "shortlist_actual_count": shortlist_count,
            "first_batch_card_count": card_count,
            "in_flight_at_freeze": work.in_flight_at_freeze,
        }

    async def finish(self, execution: Execution, work: RoundWork) -> None:
        # AI-021 drains work already started. AI-022 adds demand-driven supply and commands.
        for task in work.pending_research.values():
            await task
        for identifier, jobs in work.jobs.items():
            if jobs.long is None:
                continue
            result = await jobs.long
            await self.deliver(execution, "LONG_REVIEW", result)
            work.long_delivered.add(identifier)
            execution.counters["long_ready"] += result["status"] == "READY"
        execution.phase = "IDLE"
