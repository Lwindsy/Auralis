"""Bounded album research and evidence-grounded screening, without provider coupling."""

import asyncio
from copy import deepcopy
from html import escape
from importlib.resources import files
import json
import re
from typing import Any, Mapping

from jsonschema import Draft202012Validator

from auralis_intelligence.agents import CallDenied, ControlUnavailable, Execution
from auralis_intelligence.contracts import ContractError, validate_definition
from auralis_intelligence.providers import ModelGateway, ToolGateway
from auralis_intelligence.providers.web import FetchFailure
from .context import preference_context
from .types import AlbumDossier, ReadRecord, ResearchResult


SCREEN_SCHEMA = json.loads(files(__package__).joinpath("screen.v1.schema.json").read_text(encoding="utf-8"))
SCREEN_PROMPT = files(__package__).joinpath("screen.v1.txt").read_text(encoding="utf-8")
_MUSIC = re.compile(r"guitar|drum|rhythm|melod|vocal|synth|production|piano|bass|arrang|texture|节奏|配器|人声|音色|编曲|制作|氛围|演奏|旋律|鼓", re.I)
_REPRINT = re.compile(r"reprinted\s+(?:from|with)|originally\s+published\s+(?:at|in|on)|转载自|原文(?:刊登|发表于)|来源[：:].*转载", re.I)


def near_duplicate(left: str, right: str) -> bool:
    def shingles(text):
        normalized = "".join(character for character in text.casefold() if character.isalnum())
        return {normalized[index:index + 5] for index in range(max(0, len(normalized) - 4))}
    a, b = shingles(left), shingles(right)
    return bool(a and b) and len(a & b) / len(a | b) >= 0.85


def excerpt(text: str, byte_budget: int) -> str:
    paragraphs = [paragraph.strip() for paragraph in text.splitlines() if paragraph.strip()]
    if not paragraphs:
        return ""
    ranked = sorted(range(len(paragraphs)), key=lambda index: (index != 0, -len(_MUSIC.findall(paragraphs[index])), index))
    chosen = {}
    remaining = byte_budget
    for index in ranked:
        encoded = paragraphs[index].encode("utf-8")
        if remaining <= 0:
            break
        piece = encoded[:remaining].decode("utf-8", errors="ignore")
        if piece:
            chosen[index] = piece
            remaining -= len(piece.encode("utf-8")) + 1
    return "\n".join(chosen[index] for index in sorted(chosen))


def validate_screen(output: Any, records: list[ReadRecord], preferences: dict[str, str]) -> dict[str, Any]:
    if not Draft202012Validator(SCREEN_SCHEMA).is_valid(output):
        raise ContractError("SCREEN_STRUCTURE_INVALID")
    refs = {record.ref for record in records}
    returned = [item["ref"] for item in output["reads"]]
    if len(returned) != len(set(returned)) or set(returned) != refs:
        raise ContractError("SCREEN_READ_ALIAS_INVALID")
    qualified = {item["ref"] for item in output["reads"] if item["about_target"] == "yes" and item["substantive"] == "yes"}
    for item in output["matches"] + output["tensions"]:
        if item["pref"] not in preferences or not set(item["refs"]).issubset(qualified):
            raise ContractError("SCREEN_EVIDENCE_INVALID")
    return output


class ResearchStage:
    def __init__(self, model: ModelGateway, tools: ToolGateway, *, genre_names: Mapping[str, str] | None = None,
                 album_names: Mapping[str, str] | None = None) -> None:
        self.model, self.tools = model, tools
        self.genre_names, self.album_names = genre_names, album_names

    async def run(self, execution: Execution, candidate: dict[str, Any], preferences: dict[str, Any], *, lane: str = "FOREGROUND",
                  known_read_refs: list[dict[str, Any]] | None = None, require_score: bool = True, min_score: int | None = None) -> ResearchResult:
        validate_definition("Candidate", candidate)
        if execution.candidates.get(candidate["release_group_id"]) != candidate:
            raise ContractError("NOT_IN_BUNDLE")
        dossier = AlbumDossier(candidate["release_group_id"], deepcopy(candidate["identity"]))
        execution.dossiers[dossier.candidate_id] = dossier
        execution.counters["researched"] += 1
        timeout_key = "research_timeout_fg_ms" if lane == "FOREGROUND" else "research_timeout_bg_ms"
        try:
            result = await asyncio.wait_for(self._run(execution, candidate, preferences, dossier, lane, known_read_refs, require_score, min_score), execution.start["agent_policy"]["timing"][timeout_key] / 1000)
        except asyncio.CancelledError:
            dossier.clear()
            raise
        except (asyncio.TimeoutError, TimeoutError):
            result = self._failed(candidate, dossier, "READ_FAILED", "RESEARCH_TIMEOUT")
        except (CallDenied, ControlUnavailable, ContractError) as error:
            code = str(error) if isinstance(error, (CallDenied, ContractError)) else "CONTROL_UNAVAILABLE"
            result = self._failed(candidate, dossier, "SCREEN_FAILED" if sum(record.deterministic_pass for record in dossier.reads) >= 2 else "READ_FAILED", code)
        dossier.screening = result.screening
        validate_definition("CandidateScreening", result.screening)
        if result.screening["research_status"] == "QUALIFIED_READS" and result.screening["screen"]["status"] == "OK":
            execution.counters["qualified"] += 1
        reusable = result.screening["screen"]["status"] == "OK" and result.screening["research_status"] == "QUALIFIED_READS" and result.screening["screen"]["score"] >= min(execution.start["agent_policy"]["screening"]["shortlist_min_score"], execution.start["agent_policy"]["screening"]["supplement_min_score"])
        if not result.eligible and not reusable:
            dossier.clear()
        return result

    async def _run(self, execution, candidate, preferences, dossier, lane, known_read_refs, require_score, min_score):
        policy = execution.start["agent_policy"]["screening"]
        refs = candidate["known_review_refs"] if known_read_refs is None else known_read_refs
        for reference in refs:
            validate_definition("ReadReference", reference)
        queue = [item["url"] for item in refs]
        attempted = set()
        searches = 0
        identity = candidate["identity"]
        technical_failure = False
        failure_code = None
        while len(attempted) < policy["max_reads_per_album"]:
            execution.ensure_active()
            if not queue:
                if searches >= policy["max_searches_per_album"]:
                    break
                query = f'{identity["artist_credit"]} {identity["title"]} {identity["first_release_year"] or ""} review' if searches == 0 else f'{identity["title"]} {identity["artist_credit"]} album review'
                searches += 1
                try:
                    hits = await self.tools.search(execution, candidate_id=dossier.candidate_id, query=query, lane=lane)
                except CallDenied as error:
                    if str(error) in {"SEARCH_NOT_CONFIGURED", "SEARCH_UNAVAILABLE"}:
                        technical_failure = True
                        break
                    raise
                queue.extend(hit.url for hit in sorted(hits, key=lambda hit: identity["title"].casefold() not in hit.title.casefold()) if hit.url not in attempted)
                if not queue:
                    continue
            batch = []
            while queue and len(batch) < policy["reads_per_album_initial"] and len(attempted) < policy["max_reads_per_album"]:
                url = queue.pop(0)
                if url not in attempted:
                    attempted.add(url)
                    batch.append(url)
            async def read_url(url):
                try:
                    return await self.tools.read(execution, candidate_id=dossier.candidate_id, url=url, title=identity["title"], artist=identity["artist_credit"], purpose="REREAD" if known_read_refs is not None else "SCREEN_RESEARCH")
                except FetchFailure:
                    return None
            tasks = [asyncio.create_task(read_url(url)) for url in batch]
            try:
                outcomes = await asyncio.gather(*tasks)
            finally:
                for task in tasks:
                    if not task.done():
                        task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
            for outcome in outcomes:
                if outcome is None:
                    technical_failure = True
                    continue
                record = ReadRecord(f"R{len(dossier.reads) + 1}", outcome)
                dossier.reads.append(record)
                receipt = outcome.receipt
                if receipt["status"] != "READ_OK":
                    technical_failure = True
                    failure_code = receipt["failure_reason"]
                    record.rejection = receipt["failure_reason"]
                elif len(outcome.text) < policy["min_review_chars"] or not all(receipt["checks"].values()):
                    record.rejection = "INSUFFICIENT_OR_WRONG_IDENTITY"
                elif _REPRINT.search(outcome.text):
                    record.rejection = "REPRINT"
                elif any(other.deterministic_pass and (other.result.receipt["publisher_key"] == receipt["publisher_key"] or near_duplicate(other.result.text, outcome.text)) for other in dossier.reads if other is not record):
                    record.rejection = "DUPLICATE_PUBLISHER_OR_TEXT"
                else:
                    record.deterministic_pass = True
            if sum(record.deterministic_pass for record in dossier.reads) >= 2:
                break
        records = [record for record in dossier.reads if record.deterministic_pass]
        if len(records) < 2:
            return self._failed(candidate, dossier, "READ_FAILED" if technical_failure else "INSUFFICIENT_REVIEWS", failure_code)
        context, pref_aliases = preference_context(execution, preferences, genre_names=self.genre_names, album_names=self.album_names)
        for record in records:
            record.excerpt = excerpt(record.result.text, policy["excerpt_token_cap"])
        for record in dossier.reads:
            record.result.clear()
        sources = [{"ref": record.ref, "publisher": record.result.receipt["publisher_key"], "text": f'<source ref="{record.ref}" untrusted>{escape(record.excerpt)}</source>'} for record in records]
        model_input = {**context, "album": identity, "sources": sources}
        read_ids = tuple(record.result.receipt["read_id"] for record in records)
        output = None
        reply = None
        for attempt in range(2):
            reply = await self.model.generate(execution, role="screen", purpose="SCREEN" if attempt == 0 else "STRUCTURE_REPAIR", system=SCREEN_PROMPT,
                                              input=model_input, output_schema=SCREEN_SCHEMA, prompt_version="screen.v1", candidate_id=dossier.candidate_id, read_ids=read_ids, lane=lane)
            if reply.finish == "COMPLETE":
                try:
                    output = validate_screen(reply.output, records, pref_aliases)
                    break
                except ContractError:
                    pass
            if reply.outcome != "COMPLETED" or reply.finish == "REFUSED":
                break
            model_input = {**model_input, "repair_instruction": "上一条结构或引用不合法。重新输出完整JSON，严格使用给出的别名；不得补充资料。"}
        if output is None:
            return self._failed(candidate, dossier, "SCREEN_FAILED")
        lookup = {record.ref: record for record in records}
        assessments = {item["ref"]: item for item in output["reads"]}
        qualified_ids = [lookup[item["ref"]].result.receipt["read_id"] for item in output["reads"] if item["about_target"] == "yes" and item["substantive"] == "yes"]
        def evidence(items):
            return [{"preference_id": pref_aliases[item["pref"]], "read_ids": [lookup[ref].result.receipt["read_id"] for ref in item["refs"]], "note": item["note"]} for item in items]
        qualified = len(qualified_ids) >= 2
        threshold = min_score if min_score is not None else policy["shortlist_min_score"] if lane == "FOREGROUND" else policy["supplement_min_score"]
        eligible = qualified and (not require_score or output["score"] >= threshold or execution.start["operation"] == "CURATED_REVIEWS")
        screening = {"candidate_id": dossier.candidate_id, "recall_rank": candidate["recall_rank"], "research_status": "QUALIFIED_READS" if qualified else "INSUFFICIENT_REVIEWS",
                     "read_assessments": [{"read_id": record.result.receipt["read_id"], "deterministic_pass": record.deterministic_pass, "about_target": assessments.get(record.ref, {}).get("about_target", "unclear"), "substantive": assessments.get(record.ref, {}).get("substantive", "no")} for record in dossier.reads],
                     "qualified_read_ids": qualified_ids,
                     "screen": {"status": "OK", "score": output["score"], "invocation_id": reply.invocation_id, "prompt_version": "screen.v1"},
                     "disposition": "RESERVE" if eligible else "BELOW_FLOOR" if qualified else "INELIGIBLE",
                     "reason_code": None if eligible else "BELOW_FLOOR" if qualified else "INSUFFICIENT_REVIEWS",
                     "brief": {"summary": output["summary"], "matches": evidence(output["matches"]), "tensions": evidence(output["tensions"]), "unknowns": output["unknowns"]}}
        return ResearchResult(screening, dossier, eligible)

    @staticmethod
    def _failed(candidate, dossier, reason, error_code=None):
        wire_reason = error_code if error_code in {"SOURCE_NOT_PERMITTED", "READ_NOT_PERMITTED", "SOURCE_POLICY_EXPIRED", "STALE_SOURCE_POLICY", "SOURCE_POLICY_CHANGED", "READ_AUTHORIZATION_MISMATCH", "CONTROL_UNAVAILABLE"} else reason
        screening = {"candidate_id": candidate["release_group_id"], "recall_rank": candidate["recall_rank"], "research_status": "QUALIFIED_READS" if reason == "SCREEN_FAILED" else "READ_FAILED" if reason == "READ_FAILED" else "INSUFFICIENT_REVIEWS",
                     "read_assessments": [{"read_id": record.result.receipt["read_id"], "deterministic_pass": record.deterministic_pass, "about_target": "unclear", "substantive": "no"} for record in dossier.reads],
                     "qualified_read_ids": [], "screen": {"status": "FAILED" if reason == "SCREEN_FAILED" else "NOT_RUN", "score": None, "invocation_id": None, "prompt_version": "screen.v1" if reason == "SCREEN_FAILED" else None},
                     "disposition": "INELIGIBLE", "reason_code": wire_reason, "brief": None}
        return ResearchResult(screening, dossier, False, error_code)
