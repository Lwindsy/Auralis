"""Summary-only selection with explicit BASIC fallback and shown-item exclusion."""

import asyncio
import json
from dataclasses import dataclass
from importlib.resources import files
from typing import Any, Mapping

from auralis_intelligence.agents import CallDenied, ControlUnavailable, Execution
from auralis_intelligence.album_reviews.context import preference_context
from auralis_intelligence.contracts import ContractError, validate_definition
from auralis_intelligence.providers import ModelGateway
from jsonschema import Draft202012Validator

from .prompts_en import SELECT_REPAIR_INSTRUCTION

SCHEMA = json.loads(
    files(__package__).joinpath("select.v1.schema.json").read_text(encoding="utf-8")
)
PROMPT = files(__package__).joinpath("select.v1.txt").read_text(encoding="utf-8")
PROMPT_EN = files(__package__).joinpath("select.v1.en.txt").read_text(encoding="utf-8")


@dataclass(frozen=True)
class SelectionResult:
    selection: dict[str, Any]
    analysis_mode: str
    screen_order: list[str]
    notes: dict[str, str]
    reason_code: str | None = None


class SelectStage:
    def __init__(
        self,
        model: ModelGateway,
        *,
        genre_names: Mapping[str, str] | None = None,
        album_names: Mapping[str, str] | None = None,
    ) -> None:
        self.model = model
        self.genre_names, self.album_names = genre_names, album_names

    @staticmethod
    def evidence(execution, candidates, aliases):
        """Give every candidate actual evidence without overflowing the ranking context."""
        result = {}
        for alias, item in zip(aliases, candidates):
            dossier = execution.dossiers.get(item["candidate_id"])
            if dossier is None:
                continue
            records = dossier.qualified
            allowance = 6000 // max(1, len(records))
            result[alias] = [
                {
                    "ref": r.ref,
                    "text": r.excerpt.encode("utf-8")[:allowance].decode("utf-8", errors="ignore"),
                    "url": r.result.receipt["final_url"],
                    "truncated": len(r.excerpt.encode("utf-8")) > allowance,
                }
                for r in records
            ]
        return result

    async def run(
        self,
        execution: Execution,
        screenings: list[dict[str, Any]],
        preferences: dict[str, Any],
        *,
        shown_ids: set[str] | None = None,
        lane: str = "FOREGROUND",
    ) -> SelectionResult:
        shown_ids = shown_ids or set()
        candidates = []
        for screening in screenings:
            validate_definition("CandidateScreening", screening)
            if screening["candidate_id"] not in execution.candidates:
                raise ContractError("NOT_IN_BUNDLE")
            if (
                screening["candidate_id"] not in shown_ids
                and screening["screen"]["status"] == "OK"
                and screening["disposition"] in {"SHORTLISTED", "RESERVE"}
            ):
                candidates.append(screening)
        candidates.sort(key=lambda item: (-item["screen"]["score"], item["recall_rank"]))
        order = [item["candidate_id"] for item in candidates]
        if len(order) != len(set(order)):
            raise ContractError("DUPLICATE_CANDIDATE")
        if not candidates:
            return SelectionResult(
                {"status": "NOT_RUN", "order": [], "invocation_id": None}, "BASIC", [], {}
            )
        context, _ = preference_context(
            execution, preferences, genre_names=self.genre_names, album_names=self.album_names
        )
        aliases = {f"C{index + 1}": item["candidate_id"] for index, item in enumerate(candidates)}
        summaries = []
        read_ids = set()
        for alias, screening in zip(aliases, candidates):
            brief = screening["brief"]
            if brief is None:
                raise ContractError("SCREENING_BRIEF_MISSING")
            read_ids.update(screening["qualified_read_ids"])
            for evidence in brief["matches"] + brief["tensions"]:
                read_ids.update(evidence["read_ids"])
            summaries.append(
                {
                    "c": alias,
                    "score": screening["screen"]["score"],
                    "summary": brief["summary"],
                    "matches": [item["note"] for item in brief["matches"]],
                    "tensions": [item["note"] for item in brief["tensions"]],
                    "unknowns": brief["unknowns"],
                }
            )
        payload = {
            **context,
            "target_count": execution.start["selection_config"]["recommendation_target_count"],
            "candidates": summaries,
            "evidence": self.evidence(execution, candidates, aliases),
        }
        invocation_id = None
        try:
            for attempt in range(2):
                reply = await self.model.generate(
                    execution,
                    role="select",
                    purpose="SELECT" if attempt == 0 else "STRUCTURE_REPAIR",
                    system=PROMPT_EN if execution.english else PROMPT,
                    input=payload,
                    output_schema=SCHEMA,
                    prompt_version="select.v1.en" if execution.english else "select.v1",
                    read_ids=tuple(sorted(read_ids)),
                    lane=lane,
                )
                invocation_id = reply.invocation_id
                if reply.finish == "COMPLETE" and Draft202012Validator(SCHEMA).is_valid(
                    reply.output
                ):
                    output = reply.output["order"]
                    values = [item["c"] for item in output]
                    if (
                        len(values) == len(set(values))
                        and set(values) == set(aliases)
                        and all(
                            item["verdict"] != "reject" or item["note"].strip() for item in output
                        )
                    ):
                        return SelectionResult(
                            {
                                "status": "COMPLETED",
                                "order": [
                                    {"candidate_id": aliases[item["c"]], "verdict": item["verdict"]}
                                    for item in output
                                ],
                                "invocation_id": invocation_id,
                            },
                            "PERSONALIZED",
                            order,
                            {aliases[item["c"]]: item["note"] for item in output},
                        )
                if reply.outcome != "COMPLETED" or reply.finish == "REFUSED":
                    break
                payload = {
                    **payload,
                    "repair_instruction": SELECT_REPAIR_INSTRUCTION
                    if execution.english
                    else "给出所有输入C编号的完整且无重复排列，只使用输入编号；reject必须写依据。",
                }
        except asyncio.CancelledError:
            raise
        except (
            CallDenied,
            ContractError,
            ControlUnavailable,
            asyncio.TimeoutError,
            TimeoutError,
        ) as error:
            return SelectionResult(
                {
                    "status": "TIMED_OUT"
                    if isinstance(error, (asyncio.TimeoutError, TimeoutError))
                    else "FAILED",
                    "order": [],
                    "invocation_id": invocation_id,
                },
                "BASIC",
                order,
                {},
                str(error)
                if isinstance(error, (CallDenied, ContractError))
                else "SELECT_UNAVAILABLE",
            )
        return SelectionResult(
            {"status": "FAILED", "order": [], "invocation_id": invocation_id},
            "BASIC",
            order,
            {},
            "SELECT_INVALID_OUTPUT",
        )
