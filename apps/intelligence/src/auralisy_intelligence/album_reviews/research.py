"""Bounded album research and evidence-grounded screening, without provider coupling."""

import asyncio
import json
from copy import deepcopy
from importlib.resources import files
from typing import Any, Mapping

from auralisy_intelligence.agents import CallDenied, ControlUnavailable, Execution
from auralisy_intelligence.contracts import ContractError, validate_definition
from auralisy_intelligence.providers import ModelGateway, ToolGateway
from auralisy_intelligence.providers.excerpts import excerpt as excerpt
from jsonschema import Draft202012Validator

from .context import preference_context
from .types import AlbumDossier, ReadRecord, ResearchResult

SCREEN_SCHEMA = json.loads(
    files(__package__).joinpath("screen.v1.schema.json").read_text(encoding="utf-8")
)
RESEARCH_PROMPT = files(__package__).joinpath("research.v2.txt").read_text(encoding="utf-8")
RESEARCH_PROMPT_EN = files(__package__).joinpath("research.v2.en.txt").read_text(encoding="utf-8")


def validate_screen(
    output: Any, records: list[ReadRecord], preferences: dict[str, str]
) -> dict[str, Any]:
    if not Draft202012Validator(SCREEN_SCHEMA).is_valid(output):
        raise ContractError("SCREEN_STRUCTURE_INVALID")
    refs = {record.ref for record in records}
    returned = [item["ref"] for item in output["reads"]]
    if len(returned) != len(set(returned)) or set(returned) != refs:
        raise ContractError("SCREEN_READ_ALIAS_INVALID")
    qualified = {
        item["ref"]
        for item in output["reads"]
        if item["about_target"] == "yes" and item["substantive"] == "yes"
    }
    for item in output["matches"] + output["tensions"]:
        if (
            item["pref"] not in preferences
            or len(item["refs"]) != len(set(item["refs"]))
            or not set(item["refs"]).issubset(qualified)
        ):
            raise ContractError("SCREEN_EVIDENCE_INVALID")
    return output


class ResearchStage:
    def __init__(
        self,
        model: ModelGateway,
        tools: ToolGateway,
        *,
        genre_names: Mapping[str, str] | None = None,
        album_names: Mapping[str, str] | None = None,
    ) -> None:
        self.model, self.tools = model, tools
        self.genre_names, self.album_names = genre_names, album_names

    async def run(
        self,
        execution: Execution,
        candidate: dict[str, Any],
        preferences: dict[str, Any],
        *,
        lane: str = "FOREGROUND",
        known_read_refs: list[dict[str, Any]] | None = None,
        require_score: bool = True,
        min_score: int | None = None,
    ) -> ResearchResult:
        validate_definition("Candidate", candidate)
        if execution.candidates.get(candidate["release_group_id"]) != candidate:
            raise ContractError("NOT_IN_BUNDLE")
        dossier = AlbumDossier(candidate["release_group_id"], deepcopy(candidate["identity"]))
        execution.dossiers[dossier.candidate_id] = dossier
        execution.counters["researched"] += 1
        timeout_key = "research_timeout_fg_ms" if lane == "FOREGROUND" else "research_timeout_bg_ms"
        try:
            result = await asyncio.wait_for(
                self._run(
                    execution,
                    candidate,
                    preferences,
                    dossier,
                    lane,
                    known_read_refs,
                    require_score,
                    min_score,
                ),
                execution.start["agent_policy"]["timing"][timeout_key] / 1000,
            )
            validate_definition("CandidateScreening", result.screening)
        except asyncio.CancelledError:
            dossier.clear()
            raise
        except (asyncio.TimeoutError, TimeoutError):
            result = self._failed(candidate, dossier, "READ_FAILED", "RESEARCH_TIMEOUT")
        except (CallDenied, ControlUnavailable, ContractError) as error:
            code = (
                str(error)
                if isinstance(error, (CallDenied, ContractError))
                else "CONTROL_UNAVAILABLE"
            )
            result = self._failed(
                candidate,
                dossier,
                "SCREEN_FAILED"
                if sum(record.deterministic_pass for record in dossier.reads) >= 1
                else "READ_FAILED",
                code,
            )
        dossier.screening = result.screening
        if (
            result.screening["research_status"] == "QUALIFIED_READS"
            and result.screening["screen"]["status"] == "OK"
        ):
            execution.counters["qualified"] += 1
        reusable = (
            result.screening["screen"]["status"] == "OK"
            and result.screening["research_status"] == "QUALIFIED_READS"
            and result.screening["screen"]["score"]
            >= min(
                execution.start["agent_policy"]["screening"]["shortlist_min_score"],
                execution.start["agent_policy"]["screening"]["supplement_min_score"],
            )
        )
        if not result.eligible and not reusable:
            dossier.clear()
        return result

    async def _run(
        self,
        execution,
        candidate,
        preferences,
        dossier,
        lane,
        known_read_refs,
        require_score,
        min_score,
    ):
        policy = execution.start["agent_policy"]["screening"]
        version = "research.v2.en" if execution.english else "research.v2"
        context, pref_aliases = preference_context(
            execution, preferences, genre_names=self.genre_names, album_names=self.album_names
        )
        refs = candidate["known_review_refs"] if known_read_refs is None else known_read_refs
        available = {
            item["url"]: {"url": item["url"], "title": "Known source", "snippet": ""}
            for item in refs
        }
        attempted, queries, observations = set(), set(), []
        output, reply = None, None
        invalid_outputs = 0
        # The model chooses each action from the latest observations, including failed reads.
        for turn in range(policy["max_searches_per_album"] + policy["max_reads_per_album"] + 3):
            execution.ensure_active()
            records = [record for record in dossier.reads if record.deterministic_pass]
            decision_schema = {
                "$defs": SCREEN_SCHEMA.get("$defs", {}),
                "type": "object",
                "properties": {
                    "action": {"enum": ["search", "read", "finish", "abandon"]},
                    "query": {"type": "string"},
                    "urls": {"type": "array", "items": {"type": "string"}},
                    "reason": {"type": "string"},
                    "assessment": {"anyOf": [SCREEN_SCHEMA, {"type": "null"}]},
                },
                "required": ["action", "query", "urls", "reason", "assessment"],
                "additionalProperties": False,
            }
            payload = {
                **context,
                "album": candidate["identity"],
                "available_sources": list(available.values()),
                "observations": observations,
                "remaining_searches": policy["max_searches_per_album"] - len(queries),
                "remaining_reads": policy["max_reads_per_album"] - len(attempted),
                "sources": [
                    {"ref": r.ref, "url": r.result.receipt["final_url"], "text": r.excerpt}
                    for r in records
                ],
            }
            reply = await self.model.generate(
                execution,
                role="screen",
                purpose="SCREEN",
                system=RESEARCH_PROMPT_EN if execution.english else RESEARCH_PROMPT,
                input=payload,
                output_schema=decision_schema,
                prompt_version=version,
                candidate_id=dossier.candidate_id,
                read_ids=tuple(r.result.receipt["read_id"] for r in records),
                lane=lane,
            )
            if reply.finish != "COMPLETE" or not Draft202012Validator(decision_schema).is_valid(
                reply.output
            ):
                invalid_outputs += 1
                if (
                    reply.outcome == "COMPLETED"
                    and reply.finish != "REFUSED"
                    and invalid_outputs <= 1
                ):
                    observations.append(
                        {
                            "error": "Previous action failed schema validation. Return a complete action matching the schema; assessment=null unless finishing."
                        }
                    )
                    continue
                return self._failed(
                    candidate,
                    dossier,
                    "SCREEN_FAILED",
                    (reply.diagnostic or {}).get("category", "MODEL_FAILED"),
                    prompt_version=version,
                )
            step = reply.output
            action = step["action"]
            if action == "abandon":
                return self._failed(candidate, dossier, "INSUFFICIENT_REVIEWS")
            if action == "finish":
                try:
                    output = validate_screen(step["assessment"], records, pref_aliases)
                except ContractError:
                    observations.append(
                        {
                            "error": "Assessment must cover supplied read aliases and cite only relevant evidence."
                        }
                    )
                    continue
                if not records:
                    observations.append(
                        {
                            "error": "No actual page has been read. Search summaries are not evidence."
                        }
                    )
                    continue
                break
            if action == "search":
                query = step["query"].strip()
                if (
                    not query
                    or query in queries
                    or len(queries) >= policy["max_searches_per_album"]
                ):
                    observations.append(
                        {
                            "error": "Search duplicate or search allowance exhausted. Choose another action."
                        }
                    )
                    continue
                queries.add(query)
                hits = await self.tools.search(
                    execution, candidate_id=dossier.candidate_id, query=query, lane=lane
                )
                for hit in hits:
                    available[hit.url] = {
                        "url": hit.url,
                        "title": hit.title,
                        "snippet": hit.snippet,
                    }
                observations.append({"query": query, "results": len(hits)})
            elif action == "read":
                urls = list(dict.fromkeys(step["urls"]))
                if not urls or any(url not in available or url in attempted for url in urls):
                    observations.append(
                        {"error": "Choose unread URLs present in available_sources."}
                    )
                    continue
                urls = urls[: max(0, policy["max_reads_per_album"] - len(attempted))]
                if not urls:
                    observations.append(
                        {"error": "Read allowance exhausted. Finish with evidence or abandon."}
                    )
                    continue
                attempted.update(urls)
                outcomes = await asyncio.gather(
                    *(
                        self.tools.read(
                            execution,
                            candidate_id=dossier.candidate_id,
                            url=url,
                            title=candidate["identity"]["title"],
                            artist=candidate["identity"]["artist_credit"],
                            purpose="REREAD" if known_read_refs is not None else "SCREEN_RESEARCH",
                        )
                        for url in urls
                    )
                )
                for url, result in zip(urls, outcomes):
                    record = ReadRecord(f"R{len(dossier.reads) + 1}", result)
                    dossier.reads.append(record)
                    if result.receipt["status"] == "READ_OK" and result.text.strip():
                        record.deterministic_pass = True
                        record.excerpt = result.text[: policy["excerpt_token_cap"]]
                        observations.append(
                            {
                                "url": url,
                                "read": record.ref,
                                "truncated": len(result.text) > len(record.excerpt),
                            }
                        )
                    else:
                        observations.append(
                            {
                                "url": url,
                                "error": result.receipt["failure_reason"]
                                or result.receipt["status"],
                            }
                        )
                    result.clear()
        if output is None:
            return self._failed(candidate, dossier, "INSUFFICIENT_REVIEWS")
        return self.screen_result(
            candidate,
            dossier,
            records,
            output,
            pref_aliases,
            reply.invocation_id,
            version,
            policy,
            lane,
            require_score,
            min_score,
        )

    @staticmethod
    def screen_result(
        candidate,
        dossier,
        records,
        output,
        pref_aliases,
        invocation_id,
        version,
        policy,
        lane,
        require_score,
        min_score,
    ):
        lookup = {record.ref: record for record in records}
        assessments = {item["ref"]: item for item in output["reads"]}
        qualified_ids = [
            lookup[item["ref"]].result.receipt["read_id"]
            for item in output["reads"]
            if item["about_target"] == "yes" and item["substantive"] == "yes"
        ]

        def evidence(items):
            return [
                {
                    "preference_id": pref_aliases[item["pref"]],
                    "read_ids": [lookup[ref].result.receipt["read_id"] for ref in item["refs"]],
                    "note": item["note"],
                }
                for item in items
            ]

        qualified = bool(qualified_ids)
        threshold = (
            min_score
            if min_score is not None
            else policy["shortlist_min_score"]
            if lane == "FOREGROUND"
            else policy["supplement_min_score"]
        )
        eligible = qualified and (not require_score or output["score"] >= threshold)
        screening = {
            "candidate_id": dossier.candidate_id,
            "recall_rank": candidate["recall_rank"],
            "research_status": "QUALIFIED_READS" if qualified else "INSUFFICIENT_REVIEWS",
            "read_assessments": [
                {
                    "read_id": record.result.receipt["read_id"],
                    "deterministic_pass": record.deterministic_pass,
                    "about_target": assessments.get(record.ref, {}).get("about_target", "unclear"),
                    "substantive": assessments.get(record.ref, {}).get("substantive", "no"),
                }
                for record in dossier.reads
            ],
            "qualified_read_ids": qualified_ids,
            "screen": {
                "status": "OK",
                "score": output["score"],
                "invocation_id": invocation_id,
                "prompt_version": version,
            },
            "disposition": "RESERVE" if eligible else "BELOW_FLOOR" if qualified else "INELIGIBLE",
            "reason_code": None
            if eligible
            else "BELOW_FLOOR"
            if qualified
            else "INSUFFICIENT_REVIEWS",
            "brief": {
                "summary": output["summary"],
                "matches": evidence(output["matches"]),
                "tensions": evidence(output["tensions"]),
                "unknowns": output["unknowns"],
            },
        }
        return ResearchResult(screening, dossier, eligible)

    @staticmethod
    def _failed(candidate, dossier, reason, error_code=None, *, prompt_version="research.v2"):
        wire_reason = (
            error_code
            if error_code
            in {
                "READ_UNKNOWN",
                "READ_NOT_PERMITTED",
                "READ_AUTHORIZATION_MISMATCH",
                "CONTROL_UNAVAILABLE",
            }
            else reason
        )
        screening = {
            "candidate_id": candidate["release_group_id"],
            "recall_rank": candidate["recall_rank"],
            "research_status": "QUALIFIED_READS"
            if reason == "SCREEN_FAILED" and any(r.deterministic_pass for r in dossier.reads)
            else "READ_FAILED"
            if reason == "READ_FAILED"
            else "INSUFFICIENT_REVIEWS",
            "read_assessments": [
                {
                    "read_id": record.result.receipt["read_id"],
                    "deterministic_pass": record.deterministic_pass,
                    "about_target": "unclear",
                    "substantive": "no",
                }
                for record in dossier.reads
            ],
            "qualified_read_ids": [],
            "screen": {
                "status": "FAILED" if reason == "SCREEN_FAILED" else "NOT_RUN",
                "score": None,
                "invocation_id": None,
                "prompt_version": prompt_version if reason == "SCREEN_FAILED" else None,
            },
            "disposition": "INELIGIBLE",
            "reason_code": wire_reason,
            "brief": None,
        }
        return ResearchResult(screening, dossier, False, error_code)
