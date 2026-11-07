"""Independent short/long requests and a bounded, explicit long-review tool loop."""

import asyncio
from dataclasses import dataclass
from html import escape
from importlib.resources import files
import json
from typing import Any, Mapping

from jsonschema import Draft202012Validator

from auralis_intelligence.agents import CallDenied, ControlUnavailable, Execution
from auralis_intelligence.contracts import ContractError
from auralis_intelligence.providers import ModelGateway, ToolGateway
from .context import preference_context
from .research import excerpt
from .types import AlbumDossier, ReadRecord
from .validation import SECTIONS, han_count, validate_long, validate_short


SHORT_SCHEMA = json.loads(files(__package__).joinpath("short_review.v1.schema.json").read_text(encoding="utf-8"))
LONG_SCHEMA = json.loads(files(__package__).joinpath("long_review.v1.schema.json").read_text(encoding="utf-8"))
WRITING_PROMPT = files(__package__).joinpath("writing.v1.txt").read_text(encoding="utf-8")


@dataclass(frozen=True)
class ShortWriteResult:
    status: str
    review: dict[str, Any] | None = None
    reason_code: str | None = None


@dataclass(frozen=True)
class WritingJobs:
    short: asyncio.Task
    long: asyncio.Task


def _status(error: Exception) -> str:
    if isinstance(error, (asyncio.TimeoutError, TimeoutError)):
        return "TIMED_OUT"
    if isinstance(error, CallDenied) and any(token in str(error) for token in ("BUDGET", "LANE_EXHAUSTED", "CALL_LIMIT")):
        return "BUDGET_EXHAUSTED"
    if isinstance(error, CallDenied) and str(error) in {"EXECUTION_CLOSING", "EXECUTION_CLOSED"}:
        return "CANCELLED"
    return "FAILED"


class WriteStage:
    def __init__(self, model: ModelGateway, tools: ToolGateway, *, genre_names: Mapping[str, str] | None = None,
                 album_names: Mapping[str, str] | None = None) -> None:
        self.model, self.tools = model, tools
        self.genre_names, self.album_names = genre_names, album_names

    def start(self, execution: Execution, dossier: AlbumDossier, preferences: dict[str, Any], *, lane: str = "FOREGROUND") -> WritingJobs:
        if dossier.screening is None or dossier.screening["screen"]["status"] != "OK" or len(dossier.qualified) < 2:
            raise ContractError("ADMISSION_UNVERIFIED")
        if any(not record.excerpt for record in dossier.qualified):
            raise ContractError("DOSSIER_TEXT_UNAVAILABLE")
        # Both tasks exist before either response is available. They share data,
        # never one another's generated output.
        return WritingJobs(execution.spawn(lambda: self.short(execution, dossier, preferences, lane=lane)),
                           execution.spawn(lambda: self.long(execution, dossier, preferences, priority=3)))

    def _input(self, execution, dossier, preferences, records, form):
        context, _ = preference_context(execution, preferences, genre_names=self.genre_names, album_names=self.album_names)
        return {**context, "album": dossier.identity,
                "sources": [{"ref": record.ref, "publisher": record.result.receipt["publisher_key"],
                             "text": f'<source ref="{record.ref}" untrusted>{escape(record.excerpt)}</source>'} for record in records],
                "form": form}

    async def _response(self, execution, dossier, preferences, records, form, lane, purpose, invocation_ids, *, available_sources=(), priority=0):
        schema = SHORT_SCHEMA if form == "short" else LONG_SCHEMA
        policy = execution.start["agent_policy"]["writing"]
        payload = self._input(execution, dossier, preferences, records, form)
        if form == "long":
            payload["available_sources"] = list(available_sources)
        for attempt in range(2):
            reply = await self.model.generate(execution, role="write", purpose=purpose if attempt == 0 else "STRUCTURE_REPAIR", system=WRITING_PROMPT,
                                              input=payload, output_schema=schema, prompt_version=f"{form}_review.v1", candidate_id=dossier.candidate_id,
                                              read_ids=tuple(record.result.receipt["read_id"] for record in records), lane=lane, priority=priority,
                                              timeout_ms=execution.start["agent_policy"]["timing"]["short_review_tech_timeout_ms" if form == "short" else "long_review_timeout_ms"])
            if reply.invocation_id:
                invocation_ids.append(reply.invocation_id)
            if reply.finish == "COMPLETE" and Draft202012Validator(schema).is_valid(reply.output):
                try:
                    (validate_short if form == "short" else validate_long)(reply.output, {record.ref for record in records}, policy)
                    return reply
                except ContractError:
                    pass
            if reply.outcome != "COMPLETED" or reply.finish == "REFUSED":
                raise ContractError("WRITE_MODEL_FAILED")
            payload = {**payload, "repair_instruction": "上一条未通过完整性、字数、引用或结构检查。只用已有资料重写完整输出；不要猜测新事实。"}
        raise ContractError("WRITE_INVALID_OUTPUT")

    async def short(self, execution: Execution, dossier: AlbumDossier, preferences: dict[str, Any], *, lane: str = "FOREGROUND") -> ShortWriteResult:
        try:
            reply = await self._response(execution, dossier, preferences, list(dossier.qualified), "short", lane, "WRITE_SHORT", [])
            lookup = {record.ref: record.result.receipt["read_id"] for record in dossier.qualified}
            return ShortWriteResult("READY", {"text": reply.output["text"], "citations": [{"marker": ref, "read_id": lookup[ref]} for ref in reply.output["refs"]], "invocation_id": reply.invocation_id, "prompt_version": "short_review.v1"})
        except asyncio.CancelledError:
            return ShortWriteResult("CANCELLED", reason_code="SOURCE_REVOKED" if dossier.candidate_id in execution.revoked_candidates else "WRITE_CANCELLED")
        except (CallDenied, ContractError, ControlUnavailable, asyncio.TimeoutError, TimeoutError) as error:
            return ShortWriteResult(_status(error), reason_code=str(error) if isinstance(error, (CallDenied, ContractError)) else "WRITE_UNAVAILABLE")

    async def long(self, execution: Execution, dossier: AlbumDossier, preferences: dict[str, Any], *, priority: int = 2) -> dict[str, Any]:
        invocation_ids: list[str] = []
        extra_ids: list[str] = []
        result = {"candidate_id": dossier.candidate_id, "status": "FAILED", "sections": None, "citations": [], "han_char_count": 0,
                  "extra_read_ids": extra_ids, "missing_sections": [], "invocation_ids": invocation_ids, "prompt_version": "long_review.v1", "reason_code": None}
        try:
            return await asyncio.wait_for(self._long_work(execution, dossier, preferences, result, priority), execution.start["agent_policy"]["timing"]["long_review_timeout_ms"] / 1000)
        except asyncio.CancelledError:
            result.update(status="CANCELLED", reason_code="SOURCE_REVOKED" if dossier.candidate_id in execution.revoked_candidates else "LONG_REVIEW_CANCELLED")
        except (CallDenied, ContractError, ControlUnavailable, asyncio.TimeoutError, TimeoutError) as error:
            result.update(status=_status(error), reason_code=str(error) if isinstance(error, (CallDenied, ContractError)) else "LONG_REVIEW_UNAVAILABLE")
        return result

    async def _long_work(self, execution, dossier, preferences, result, priority):
        records = list(dossier.qualified)
        available: dict[str, str] = {}
        policy = execution.start["agent_policy"]["writing"]
        searches = reads = 0
        for turn in range(policy["long_tool_turns"] + 1):
            reply = await self._response(execution, dossier, preferences, records, "long", "BACKGROUND", "WRITE_LONG" if turn == 0 else "LONG_TOOL_TURN", result["invocation_ids"], available_sources=[{"url": url, "title": title} for url, title in available.items()], priority=priority)
            output = reply.output
            if output["status"] == "ok":
                lookup = {record.ref: record.result.receipt["read_id"] for record in records}
                result.update(status="READY", sections=output["sections"], citations=[{"marker": ref, "read_id": lookup[ref]} for ref in output["refs"]], han_char_count=sum(han_count(text) for text in output["sections"].values()), missing_sections=[])
                return result
            result.update(status="NEEDS_MORE_RESEARCH", missing_sections=output["missing"], reason_code="REVIEW_MATERIAL_INSUFFICIENT")
            if turn == policy["long_tool_turns"] or not output["tool_requests"]:
                return result
            progressed = False
            for request in output["tool_requests"]:
                if request["tool"] == "web_search":
                    if request["url"] is not None:
                        raise ContractError("LONG_TOOL_ARGUMENT_INVALID")
                    if searches >= policy["long_extra_max_searches"]:
                        continue
                    searches += 1
                    query = f'{dossier.identity["artist_credit"]} {dossier.identity["title"]} {request["section"]} album review'
                    hits = await self.tools.search(execution, candidate_id=dossier.candidate_id, query=query, lane="BACKGROUND")
                    for hit in hits:
                        if hit.url not in available:
                            available[hit.url] = hit.title
                            progressed = True
                else:
                    url = request["url"]
                    if url not in available:
                        raise ContractError("LONG_TOOL_URL_NOT_IN_RESULTS")
                    if reads >= policy["long_extra_max_reads"]:
                        continue
                    reads += 1
                    read = await self.tools.read(execution, candidate_id=dossier.candidate_id, url=url, title=dossier.identity["title"], artist=dossier.identity["artist_credit"], purpose="LONG_REVIEW_RESEARCH", priority=priority)
                    if read.receipt["status"] != "READ_OK":
                        raise ContractError("LONG_RESEARCH_FAILED")
                    if not all(read.receipt["checks"].values()) or read.receipt["text_chars"] < execution.start["agent_policy"]["screening"]["min_review_chars"]:
                        read.clear()
                        continue
                    record = ReadRecord(f"R{len(dossier.reads) + 1}", read, excerpt(read.text, execution.start["agent_policy"]["screening"]["excerpt_token_cap"]), True)
                    read.clear()
                    dossier.reads.append(record)
                    records.append(record)
                    result["extra_read_ids"].append(read.receipt["read_id"])
                    progressed = True
            if not progressed:
                return result
        return result
