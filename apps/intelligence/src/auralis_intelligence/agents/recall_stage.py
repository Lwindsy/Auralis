"""A-V2 adapter with bounded multi-genre pool coverage; Java validates admission."""

import asyncio
from copy import deepcopy
from dataclasses import asdict, replace
from pathlib import Path

from auralis_intelligence.contracts import (
    ContractError,
    content_digest,
    digest,
    require_binding,
    validate_definition,
)
from auralis_intelligence.recall import (
    Preference,
    RecallEngine,
    RecallRequest,
    WeightConfig,
    load_a_v2,
)

from .resources import drain
from .runtime import Execution


async def _drain_worker(worker: asyncio.Future):
    """Keep ownership through cancellation, including while draining a timeout."""
    cancelled = False
    while not worker.done():
        try:
            await asyncio.shield(worker)
        except asyncio.CancelledError:
            cancelled = True
        except Exception:
            break  # Retrieve and propagate the worker error below.
    if cancelled:
        if not worker.cancelled():
            worker.exception()  # Observe any error before propagating cancellation.
        raise asyncio.CancelledError
    return worker.result()


def recall_request(snapshot: dict, exclusions: list[dict], limit: int) -> RecallRequest:
    validate_definition("EffectivePreferenceSnapshot", snapshot)
    return RecallRequest(
        limit=limit,
        seeds=tuple(Preference(**row) for row in snapshot["seeds"]),
        tags=tuple(Preference(**row) for row in snapshot["tags"]),
        required_tags=tuple(snapshot["required_tags"]),
        forbidden_tags=tuple(snapshot["forbidden_tags"]),
        disliked_tags=tuple(snapshot["disliked_tags"]),
        disliked_exceptions=tuple(
            dict.fromkeys(
                row["genre_id"]
                for row in snapshot["overrides"]
                if row["action"] in {"PREFER", "REQUIRE"}
            )
        ),
        excluded_ids=tuple(row["release_group_id"] for row in exclusions),
        mode=snapshot["mode"],
    )


class RecallStage:
    def __init__(
        self,
        snapshot: Path,
        *,
        binding: dict,
        weights: WeightConfig,
        source_dependencies: tuple[str, ...] = (),
        engine: RecallEngine | None = None,
        min_listeners: int | None = None,
        max_listeners: int | None = None,
    ) -> None:
        validate_definition("RecallBinding", binding)
        if binding["weights_digest"] != digest(asdict(weights)):
            raise ContractError("BINDING_MISMATCH")
        self.snapshot = Path(snapshot)
        self.binding = deepcopy(binding)
        self.weights = weights
        self.source_dependencies = tuple(source_dependencies)
        self.audience_bounds: dict[str, int] = {}
        if min_listeners is not None:
            self.audience_bounds["min_listeners"] = min_listeners
            self.source_dependencies += (f"listenbrainz-distinct-listeners>={min_listeners}",)
        if max_listeners is not None:
            self.audience_bounds["max_listeners"] = max_listeners
            self.source_dependencies += (f"listenbrainz-distinct-listeners<={max_listeners}",)
        self.engine = engine

    def _compute(self, request: RecallRequest):
        if self.engine is None:
            try:
                self.engine = load_a_v2(self.snapshot, weights=self.weights)
            except (OSError, ValueError, KeyError, TypeError, MemoryError):
                return "UNAVAILABLE", "artifact_unavailable", None
        try:
            result = self.engine.recall(request, **self.audience_bounds)
        except ValueError:
            return "UNSUPPORTED_INPUT", "unsupported_input", None
        except TimeoutError:
            return "TIMED_OUT", "recall_timed_out", None
        except (OSError, MemoryError):
            return "UNAVAILABLE", "recall_unavailable", None
        if not request.seeds and len(request.tags) > 1 and result.returned_count:
            # Keep the scorer unchanged. Reserve at most one research opportunity per
            # explicit direction, then fill from the joint ranking without comparing
            # scores produced by different queries.
            covered, seen = [], set()
            priorities = sorted(
                request.tags, key=lambda tag: (not tag.focused, tag.strength != "special")
            )
            for tag in priorities[: request.limit]:
                constrained = replace(
                    request,
                    limit=1,
                    tags=(tag,),
                    required_tags=tuple(dict.fromkeys((*request.required_tags, tag.id))),
                )
                try:
                    one = self.engine.recall(constrained, **self.audience_bounds)
                except ValueError:
                    continue  # A genre absent from this snapshot cannot add a candidate.
                except TimeoutError:
                    return "TIMED_OUT", "recall_timed_out", None
                except (OSError, MemoryError):
                    return "UNAVAILABLE", "recall_unavailable", None
                for candidate in one.candidates:
                    if candidate.album_id not in seen:
                        covered.append(candidate)
                        seen.add(candidate.album_id)
            for candidate in result.candidates:
                if len(covered) >= request.limit:
                    break
                if candidate.album_id not in seen:
                    covered.append(candidate)
                    seen.add(candidate.album_id)
            result = replace(
                result,
                candidates=tuple(covered),
                returned_count=len(covered),
                reason="complete" if len(covered) == request.limit else "insufficient_candidates",
            )
        if (
            result.dataset_id != self.binding["data_snapshot_id"]
            or result.window != self.binding["window"]
            or result.algorithm != "A-V2"
        ):
            raise ContractError("BINDING_MISMATCH")
        if result.reason == "unsupported_positive_input":
            return "UNSUPPORTED_INPUT", result.reason, None
        if result.reason not in {"complete", "insufficient_candidates"}:
            raise ContractError("UNKNOWN_RECALL_RESULT")
        return ("COMPLETE" if result.returned_count else "NO_COVERAGE"), result.reason, result

    async def __call__(self, execution: Execution, preferences: dict) -> dict:
        with execution.clock.measure("recall"):
            return await self._recall(execution, preferences)

    async def _recall(self, execution: Execution, preferences: dict) -> dict:
        execution.ensure_active()
        if execution.start["start_mode"] == "CONTINUE":
            raise ContractError("RECALL_NOT_ALLOWED")
        require_binding(self.binding, execution.start["recall_binding"])
        if self.audience_bounds:
            if self.engine is None or self.engine.audience_binding is None:
                raise ContractError("FULL_AUDIENCE_REQUIRED")
            policy = execution.start["agent_policy"]
            require_binding(
                self.engine.audience_binding,
                policy.get("audience_binding"),
            )
            for key in ("min_listeners", "max_listeners"):
                if policy.get(f"audience_{key}") != self.audience_bounds.get(key):
                    raise ContractError("BINDING_MISMATCH")
        require_binding(asdict(self.weights), execution.start["agent_policy"]["recall"]["weights"])
        if execution.start["input"]["accepted_preferences"] is None:
            raise ContractError("MISSING_PREFERENCES")
        require_binding(preferences, execution.start["input"]["accepted_preferences"])
        request = recall_request(
            preferences,
            execution.start["input"]["exclusions"],
            execution.start["selection_config"]["recall_target_count"],
        )
        execution.phase = "RECALLING"
        if not preferences["has_positive_signal"]:
            status, reason, result = "UNSUPPORTED_INPUT", "unsupported_positive_input", None
        else:
            # SciPy CPU calls cannot be killed safely. Drain before slot release;
            # never allow a cancelled thread to keep using the next round's slot.
            worker = asyncio.create_task(execution.scheduler.cpu(self._compute, request))
            try:
                status, reason, result = await asyncio.wait_for(
                    asyncio.shield(worker),
                    execution.start["agent_policy"]["timing"]["recall_timeout_ms"] / 1000,
                )
            except asyncio.TimeoutError:
                worker.cancel()
                # Drain physical CPU work, but retain cancellation arriving during that drain.
                await _drain_worker(asyncio.gather(worker, return_exceptions=True))
                status, reason, result = "TIMED_OUT", "recall_timed_out", None
            except asyncio.CancelledError:
                worker.cancel()
                await drain([worker])
                raise
        execution.ensure_active()
        candidates = (
            []
            if result is None
            else [
                {"release_group_id": row.album_id, "recall_rank": rank}
                for rank, row in enumerate(result.candidates, 1)
            ]
        )
        proposal = {
            "recall_binding": deepcopy(self.binding),
            "input_digest": preferences["input_digest"],
            "effective_preference_digest": preferences["effective_preference_digest"],
            "exclusion_digest": execution.start["input"]["exclusion_digest"],
            "requested_count": request.limit,
            "returned_count": len(candidates),
            "execution_status": status,
            "raw_reason": reason,
            "shortfall": request.limit - len(candidates),
            "truncated": False,
            "candidates": candidates,
            "source_dependencies": list(self.source_dependencies)
            + (
                ["composition:multi-genre-coverage.v1"]
                if not request.seeds and len(request.tags) > 1
                else []
            )
            + (
                ["audience:" + self.engine.audience_binding["snapshot_digest"]]
                if self.audience_bounds
                else []
            ),
        }
        proposal["proposal_digest"] = content_digest(proposal, "proposal_digest")
        validate_definition("RecallProposal", proposal)
        return proposal
