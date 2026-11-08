"""One grounded decision produces rankings and three cards from prepared public evidence."""

import asyncio
from importlib.resources import files

from auralis_intelligence.contracts import ContractError
from auralis_intelligence.preferences.schemas import array, object_schema
from auralis_intelligence.ranking import SelectionResult, SelectStage

from .context import preference_context
from .research import ResearchStage, excerpt
from .types import AlbumDossier, ReadRecord, ResearchResult
from .validation import validate_short
from .writing import ShortWriteResult, WriteStage

PROMPT = files(__package__).joinpath("batch.v2.txt").read_text(encoding="utf-8")
REFS = array({"type": "string"}, maximum=4)
SCHEMA = object_schema(
    {
        "cards": array(
            object_schema(
                {
                    "c": {"type": "string"},
                    "score": {"type": "integer", "minimum": 3, "maximum": 5},
                    "text": {"type": "string"},
                    "refs": REFS,
                }
            ),
            maximum=10,
        )
    }
)


class BatchResearchStage:
    prepared_decisions = True

    def __init__(
        self, model, tools, evidence, *, genre_names=None, album_names=None, candidate_limit=8
    ):
        self.model, self.tools, self.evidence = model, tools, evidence
        self.genre_names, self.album_names = genre_names, album_names
        self.candidate_limit = candidate_limit
        self.task = None
        self.execution_id = None
        self.shorts, self.order, self.notes = {}, [], {}
        self.invocation_id = None
        self.statistics = {}

    async def run(self, execution, candidate, preferences, **kwargs):
        if self.execution_id not in (None, execution.id):
            raise ContractError("BATCH_STAGE_EXECUTION_MISMATCH")
        self.execution_id = execution.id
        if (
            self.task is not None
            and self.task.done()
            and execution.work is not None
            and set(self.order).issubset(execution.work.shown | execution.work.heard)
            and candidate["release_group_id"] not in self.order
        ):
            self.task = None
        if self.task is None:
            self.task = execution.spawn(lambda: self._evaluate(execution, preferences))
        results = await asyncio.shield(self.task)
        return results[candidate["release_group_id"]]

    async def next_batch(self, execution, work):
        self.task = execution.spawn(lambda: self._evaluate(execution, work.preferences))
        return await asyncio.shield(self.task)

    async def _evaluate(self, execution, preferences):
        if execution.start["agent_policy"].get("audience_min_listeners") != 50:
            raise ContractError("AUDIENCE_POLICY_REQUIRED")
        excluded = (
            execution.work.shown | execution.work.heard if execution.work is not None else set()
        )
        candidates = [
            c for c in execution.candidates.values() if c["release_group_id"] not in excluded
        ]
        if getattr(self.evidence, "cold", False):
            eligible_sources = [
                c
                for c in candidates
                if c.get("audience", {}).get("listeners") is not None
                and c["audience"]["listeners"] >= 50
            ]
            searchable, artists = [], set()
            for candidate in eligible_sources:
                artist = candidate["identity"]["artist_credit"].casefold()
                if artist not in artists:
                    searchable.append(candidate)
                    artists.add(artist)
                if len(searchable) == self.candidate_limit:
                    break
            if len(searchable) < self.candidate_limit:
                chosen_ids = {c["release_group_id"] for c in searchable}
                searchable += [
                    c for c in eligible_sources if c["release_group_id"] not in chosen_ids
                ][: self.candidate_limit - len(searchable)]
            await self.evidence.prepare(execution, searchable, self.tools)
        results, prepared, audiences = {}, [], {}
        audience_passed = 0
        for candidate in candidates:
            identifier = candidate["release_group_id"]
            dossier = AlbumDossier(identifier, candidate["identity"])
            execution.dossiers[identifier] = dossier
            audience = candidate.get("audience")
            reason = "EVIDENCE_NOT_PREPARED"
            if getattr(self.evidence, "cold", False):
                reason = (
                    "READ_FAILED"
                    if identifier in self.evidence.failed_ids
                    else "INSUFFICIENT_REVIEWS"
                )
            if (
                not audience
                or audience["listeners"] is None
                or audience["dataset_id"] != execution.start["recall_binding"]["data_snapshot_id"]
                or audience["window"] != execution.start["recall_binding"]["window"]
            ):
                reason = "AUDIENCE_UNKNOWN"
            elif audience["listeners"] < 50:
                reason = "AUDIENCE_BELOW_MINIMUM"
            else:
                audience_passed += 1
                sources = self.evidence.sources(identifier)
                if sources:
                    reason = "NOT_SELECTED"
                if sources and len(prepared) < self.candidate_limit:
                    prepared.append((candidate, dossier, sources[:2]))
                    audiences[identifier] = audience
            failed = ResearchStage._failed(candidate, dossier, "INSUFFICIENT_REVIEWS")
            failed.screening["reason_code"] = reason
            if reason == "NOT_SELECTED":
                failed.screening["research_status"] = "DEFERRED"
                failed.screening["disposition"] = "DEFERRED"
            results[identifier] = failed
        execution.counters["researched"] += len(prepared)

        async def load(candidate, dossier, sources):
            downloaded = (
                self.evidence.take_reads(dossier.candidate_id)
                if getattr(self.evidence, "cold", False)
                else None
            )
            if downloaded is not None:
                for result in downloaded:
                    if result.receipt["status"] == "READ_OK":
                        dossier.reads.append(
                            ReadRecord(
                                f"R{len(dossier.reads) + 1}",
                                result,
                                excerpt(result.text, 3500),
                                True,
                            )
                        )
                    result.clear()
                return candidate, dossier
            for source in sources:
                result = await self.tools.read(
                    execution,
                    candidate_id=dossier.candidate_id,
                    url=source["url"],
                    title=dossier.identity["title"],
                    artist=dossier.identity["artist_credit"],
                    purpose="SCREEN_RESEARCH",
                )
                if result.receipt["status"] == "READ_OK":
                    record = ReadRecord(
                        f"R{len(dossier.reads) + 1}", result, excerpt(result.text, 3500), True
                    )
                    result.clear()
                    dossier.reads.append(record)
            return candidate, dossier

        loaded = await asyncio.gather(*(load(*row) for row in prepared))
        loaded = [(candidate, dossier) for candidate, dossier in loaded if dossier.reads]
        self.statistics = {
            "audience_checked": len(candidates),
            "audience_passed": audience_passed,
            "comparison_candidates": len(loaded),
        }
        if not loaded:
            return results
        context, _ = preference_context(
            execution, preferences, genre_names=self.genre_names, album_names=self.album_names
        )
        aliases = {f"C{i + 1}": row for i, row in enumerate(loaded)}
        payload = {
            **context,
            "target_count": execution.start["selection_config"]["recommendation_target_count"],
            "candidates": [
                {
                    "c": alias,
                    "album": dossier.identity,
                    "sample_listeners": audiences[dossier.candidate_id]["listeners"],
                    "sources": [
                        {"ref": r.ref, "text": excerpt(r.excerpt, 1800)} for r in dossier.reads
                    ],
                }
                for alias, (_, dossier) in aliases.items()
            ],
        }
        read_ids = tuple(
            r.result.receipt["read_id"] for _, dossier in loaded for r in dossier.reads
        )
        output = None
        for attempt in range(2):
            reply = await self.model.generate(
                execution,
                role="select",
                purpose="SCREEN" if not attempt else "STRUCTURE_REPAIR",
                system=PROMPT
                + (
                    "\n固定策展：按输入顺序为前target_count张资料充分的作品写卡片，不能按偏好改选或重排。"
                    if execution.start["operation"] == "CURATED_REVIEWS"
                    else ""
                ),
                input=payload,
                output_schema=SCHEMA,
                prompt_version="batch.v2",
                read_ids=read_ids,
            )
            if reply.finish == "COMPLETE":
                try:
                    self._validate(
                        reply.output,
                        aliases,
                        payload["target_count"],
                        execution.start["agent_policy"]["writing"],
                        curated=execution.start["operation"] == "CURATED_REVIEWS",
                    )
                    output = reply.output
                    break
                except (ContractError, KeyError, TypeError) as error:
                    payload = {
                        **payload,
                        "repair": "修复格式或引用；短评尽量约60汉字，最多3句；候选编号和引用必须来自输入。错误："
                        + str(error),
                    }
            if reply.outcome != "COMPLETED" or reply.finish == "REFUSED":
                break
        if output is None:
            raise ContractError("BATCH_RECOMMENDATION_FAILED")
        self.invocation_id = reply.invocation_id
        self.order = []
        chosen = {row["c"] for row in output["cards"]}
        for row in output["cards"]:
            candidate, dossier = aliases[row["c"]]
            identifier = dossier.candidate_id
            refs = set(row["refs"])
            qualified = [r.result.receipt["read_id"] for r in dossier.reads if r.ref in refs]
            score = row["score"]
            screening = {
                "candidate_id": identifier,
                "recall_rank": candidate["recall_rank"],
                "research_status": "QUALIFIED_READS" if qualified else "INSUFFICIENT_REVIEWS",
                "read_assessments": [
                    {
                        "read_id": r.result.receipt["read_id"],
                        "deterministic_pass": True,
                        "about_target": "yes" if r.ref in refs else "unclear",
                        "substantive": "yes" if r.ref in refs else "no",
                    }
                    for r in dossier.reads
                ],
                "qualified_read_ids": qualified,
                "screen": {
                    "status": "OK",
                    "score": score,
                    "invocation_id": reply.invocation_id,
                    "prompt_version": "batch.v2",
                },
                "disposition": "RESERVE"
                if qualified and score >= 3
                else "BELOW_FLOOR"
                if qualified
                else "INELIGIBLE",
                "reason_code": None
                if qualified and score >= 3
                else "BELOW_FLOOR"
                if qualified
                else "INSUFFICIENT_REVIEWS",
                "brief": {"summary": row["text"], "matches": [], "tensions": [], "unknowns": []},
            }
            dossier.screening = screening
            results[identifier] = ResearchResult(screening, dossier, row["c"] in chosen)
            self.notes[identifier] = row["text"]
        for row in output["cards"]:
            _, dossier = aliases[row["c"]]
            lookup = {r.ref: r.result.receipt["read_id"] for r in dossier.qualified}
            self.order.append(dossier.candidate_id)
            self.shorts[dossier.candidate_id] = ShortWriteResult(
                "READY",
                {
                    "text": row["text"],
                    "citations": [{"marker": ref, "read_id": lookup[ref]} for ref in row["refs"]],
                    "invocation_id": reply.invocation_id,
                    "prompt_version": "batch.v2",
                },
            )
        execution.counters["qualified"] += sum(
            r.screening["reason_code"] is None for r in results.values()
        )
        return results

    @staticmethod
    def _validate(output, aliases, count, policy, *, curated=False):
        cards = output["cards"]
        if len(cards) > count or len({r["c"] for r in cards}) != len(cards):
            raise ContractError("BATCH_CARDS_INVALID")
        if curated:
            order = {alias: i for i, alias in enumerate(aliases)}
            positions = [order.get(card["c"], -1) for card in cards]
            if positions != sorted(positions):
                raise ContractError("CURATED_ORDER_CHANGED")
        for card in cards:
            if card["c"] not in aliases or card["score"] < 3 or not card["refs"]:
                raise ContractError("BATCH_ADMISSION_INVALID")
            available = {r.ref for r in aliases[card["c"]][1].reads}
            validate_short({"text": card["text"], "refs": card["refs"]}, available, policy)


class BatchWriteStage(WriteStage):
    def __init__(self, *args, batch, **kwargs):
        super().__init__(*args, **kwargs)
        self.batch = batch

    async def short(self, execution, dossier, preferences, **kwargs):
        if dossier.candidate_id in self.batch.shorts:
            return self.batch.shorts[dossier.candidate_id]
        return await super().short(execution, dossier, preferences, **kwargs)


class BatchSelectStage(SelectStage):
    def __init__(self, *args, batch, **kwargs):
        super().__init__(*args, **kwargs)
        self.batch = batch

    async def run(self, execution, screenings, preferences, **kwargs):
        ids = {s["candidate_id"] for s in screenings}
        if ids == set(self.batch.order) and not ids.intersection(kwargs.get("shown_ids") or set()):
            return SelectionResult(
                {
                    "status": "COMPLETED",
                    "order": [
                        {"candidate_id": i, "verdict": "recommend"} for i in self.batch.order
                    ],
                    "invocation_id": self.batch.invocation_id,
                },
                "PERSONALIZED",
                self.batch.order,
                self.batch.notes,
            )
        return await super().run(execution, screenings, preferences, **kwargs)
