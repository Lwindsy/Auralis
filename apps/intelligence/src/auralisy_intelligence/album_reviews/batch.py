"""One grounded decision produces rankings and three cards from prepared public evidence."""

import asyncio
from importlib.resources import files

from auralisy_intelligence.contracts import ContractError
from auralisy_intelligence.preferences.schemas import array, object_schema
from auralisy_intelligence.ranking import SelectionResult, SelectStage
from auralisy_intelligence.recall.full_audience import matches_policy

from .context import preference_context
from .prompts_en import BATCH_REPAIR
from .research import ResearchStage, excerpt
from .types import AlbumDossier, ReadRecord, ResearchResult
from .validation import validate_short
from .writing import ShortWriteResult, WriteStage

PROMPT = files(__package__).joinpath("batch.v7.txt").read_text(encoding="utf-8")
PROMPT_EN = files(__package__).joinpath("batch.v7.en.txt").read_text(encoding="utf-8")
REFS = array({"type": "string"}, maximum=4)
SCHEMA = object_schema(
    {
        "assessments": array(
            object_schema(
                {
                    "c": {"type": "string"},
                    "reason": {
                        "type": "string",
                        "enum": [
                            "SUFFICIENT",
                            "WRONG_WORK",
                            "NO_MUSIC_DESCRIPTION",
                            "INSUFFICIENT_DETAIL",
                            "UNRESOLVED_CONFLICT",
                        ],
                    },
                    "refs": REFS,
                }
            ),
            maximum=10,
        ),
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
        ),
    }
)


class BatchResearchStage:
    prepared_decisions = True

    def __init__(
        self, model, tools, evidence, *, genre_names=None, album_names=None, candidate_limit=8
    ):
        self.model, self.tools, self.evidence = model, tools, evidence
        if getattr(evidence, "cold", False):
            evidence.keep_background = True
        self.genre_names, self.album_names = genre_names, album_names
        self.candidate_limit = candidate_limit
        self.task = None
        self.execution_id = None
        self.shorts, self.order, self.notes = {}, [], {}
        self.invocation_id = None
        self.statistics = {}

    async def run(self, execution, candidate, preferences, **kwargs):
        if kwargs.get("require_score") is False:
            # Resumed long reviews hydrate an already selected album, not a new batch.
            return await ResearchStage(
                self.model, self.tools, genre_names=self.genre_names, album_names=self.album_names
            ).run(execution, candidate, preferences, **kwargs)
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

    def _research_window(self, candidates, preferences):
        selected, identifiers, artists = [], set(), set()
        wanted = {tag["id"] for tag in preferences["tags"]}
        remaining = set(wanted)
        # Genre labels allocate research opportunities, not factual genre admission.
        while remaining and len(selected) < self.candidate_limit:
            best, coverage = None, set()
            for candidate in candidates:
                if candidate["release_group_id"] in identifiers:
                    continue
                overlap = remaining & {
                    genre["genre_id"] for genre in candidate["identity"]["primary_genres"]
                }
                if len(overlap) > len(coverage):
                    best, coverage = candidate, overlap
            if best is None:
                break
            selected.append(best)
            identifiers.add(best["release_group_id"])
            artists.add(best["identity"]["artist_credit"].casefold())
            remaining -= coverage
        for unique_artist in (True, False):
            for candidate in candidates:
                if len(selected) == self.candidate_limit:
                    return selected
                artist = candidate["identity"]["artist_credit"].casefold()
                if candidate["release_group_id"] in identifiers or (
                    unique_artist and artist in artists
                ):
                    continue
                selected.append(candidate)
                identifiers.add(candidate["release_group_id"])
                artists.add(artist)
        return selected

    async def _evaluate(self, execution, preferences):
        self.order = []
        policy = execution.start["agent_policy"]
        minimum = policy.get("audience_min_listeners")
        maximum = policy.get("audience_max_listeners", float("inf"))
        if minimum is None:
            raise ContractError("AUDIENCE_POLICY_REQUIRED")
        excluded = (
            execution.work.shown | execution.work.heard if execution.work is not None else set()
        )
        if execution.work is not None:
            excluded |= {
                identifier
                for identifier, result in execution.work.results.items()
                if result.screening["disposition"] in {"INELIGIBLE", "BELOW_FLOOR"}
            }
        candidates = [
            c for c in execution.candidates.values() if c["release_group_id"] not in excluded
        ]
        searched = set()
        if getattr(self.evidence, "cold", False):
            eligible_sources = [
                c
                for c in candidates
                if matches_policy(c.get("audience"), policy)
                and minimum <= c["audience"]["listeners"] <= maximum
            ]
            searchable = self._research_window(eligible_sources, preferences)
            await self.evidence.prepare(execution, searchable, self.tools)
            searched = {c["release_group_id"] for c in searchable}
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
            if not matches_policy(audience, policy):
                reason = "AUDIENCE_UNKNOWN"
            elif audience["listeners"] < minimum:
                reason = "AUDIENCE_BELOW_MINIMUM"
            elif audience["listeners"] > maximum:
                reason = "AUDIENCE_ABOVE_MAXIMUM"
            else:
                audience_passed += 1
                sources = self.evidence.sources(identifier)
                if getattr(self.evidence, "cold", False) and identifier not in searched:
                    reason = "NOT_SELECTED"
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
                    "album": {
                        key: value
                        for key, value in dossier.identity.items()
                        if key != "primary_genres"
                    },
                    "catalog_genre_hints": dossier.identity.get("primary_genres", []),
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
        version = "batch.v7.en" if execution.english else "batch.v7"
        for attempt in range(2):
            reply = await self.model.generate(
                execution,
                role="select",
                purpose="SELECT" if not attempt else "STRUCTURE_REPAIR",
                system=PROMPT_EN if execution.english else PROMPT,
                input=payload,
                output_schema=SCHEMA,
                prompt_version=version,
                read_ids=read_ids,
            )
            if reply.finish == "COMPLETE":
                try:
                    self._validate(
                        reply.output,
                        aliases,
                        payload["target_count"],
                        execution.start["agent_policy"]["writing"],
                        english=execution.english,
                    )
                    output = reply.output
                    break
                except (ContractError, KeyError, TypeError) as error:
                    payload = {
                        **payload,
                        "repair": (
                            BATCH_REPAIR
                            if execution.english
                            else "修复格式或引用；短评尽量约60汉字，最多3句；候选编号和引用必须来自输入。错误："
                        )
                        + str(error),
                    }
            if reply.outcome != "COMPLETED" or reply.finish == "REFUSED":
                break
        if output is None:
            raise ContractError("BATCH_RECOMMENDATION_FAILED")
        if getattr(self.evidence, "cold", False):
            selected_ids = {aliases[row["c"]][1].candidate_id for row in output["cards"]}
            execution.spawn(lambda: self.evidence.discard_unselected(selected_ids))
        self.invocation_id = reply.invocation_id
        self.order = []
        chosen = {row["c"] for row in output["cards"]}
        self.statistics["evidence_assessments"] = {
            aliases[row["c"]][1].candidate_id: {"reason": row["reason"], "refs": row["refs"]}
            for row in output["assessments"]
        }
        for row in output["assessments"]:
            if row["reason"] != "SUFFICIENT":
                candidate, dossier = aliases[row["c"]]
                results[dossier.candidate_id] = ResearchStage._failed(
                    candidate, dossier, "INSUFFICIENT_REVIEWS"
                )
                results[dossier.candidate_id].screening["reason_code"] = row["reason"]

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
                    "prompt_version": self.model.version(version)
                    if hasattr(self.model, "version")
                    else version,
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
                    "prompt_version": self.model.version(version)
                    if hasattr(self.model, "version")
                    else version,
                },
            )
        execution.counters["qualified"] += sum(
            r.screening["reason_code"] is None for r in results.values()
        )
        return results

    @staticmethod
    def _validate(output, aliases, count, policy, *, english=False):
        assessments = output["assessments"]
        if len(assessments) != len(aliases) or {r["c"] for r in assessments} != set(aliases):
            raise ContractError("BATCH_ASSESSMENTS_INCOMPLETE")
        assessed = {r["c"]: r for r in assessments}
        for row in assessments:
            available = {r.ref for r in aliases[row["c"]][1].reads}
            if not set(row["refs"]).issubset(available) or (
                row["reason"] == "SUFFICIENT" and not row["refs"]
            ):
                raise ContractError("BATCH_EVIDENCE_REFS_INVALID")
        cards = output["cards"]
        if len(cards) > count or len({r["c"] for r in cards}) != len(cards):
            raise ContractError("BATCH_CARDS_INVALID")
        for card in cards:
            if card["c"] not in aliases or card["score"] < 3 or not card["refs"]:
                raise ContractError("BATCH_ADMISSION_INVALID")
            assessment = assessed[card["c"]]
            if assessment["reason"] != "SUFFICIENT" or not set(card["refs"]).issubset(
                assessment["refs"]
            ):
                raise ContractError("BATCH_EVIDENCE_INSUFFICIENT")
            available = {r.ref for r in aliases[card["c"]][1].reads}
            validate_short(
                {"text": card["text"], "refs": card["refs"]}, available, policy, english=english
            )


class BatchWriteStage(WriteStage):
    def __init__(self, *args, batch, **kwargs):
        super().__init__(*args, **kwargs)
        self.batch = batch

    async def additional_records(self, execution, dossier):
        if not getattr(self.batch.evidence, "cold", False):
            return []
        extra = await self.batch.evidence.take_background_reads(dossier.candidate_id)
        records = []
        seen = {r.result.receipt["content_digest"] for r in dossier.reads}
        for read in extra:
            if read.receipt["content_digest"] not in seen:
                seen.add(read.receipt["content_digest"])
                record = ReadRecord(
                    f"R{len(dossier.reads) + 1}", read, excerpt(read.text, 3500), True
                )
                dossier.reads.append(record)
                records.append(record)
            read.clear()
        return records

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
