"""Lossless A-V2 adapter; Java still validates candidates and routes no-positive input."""

import asyncio
from copy import deepcopy
from dataclasses import asdict
from pathlib import Path

from auralis_intelligence.contracts import ContractError, content_digest, digest, require_binding, validate_definition
from auralis_intelligence.recall import Preference, RecallEngine, RecallRequest, WeightConfig, load_a_v2
from .runtime import Execution


async def _drain_worker(worker: asyncio.Task, *, cancelled: bool = False):
    """Keep ownership through cancellation, including while draining a timeout."""
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
    return RecallRequest(limit=limit, seeds=tuple(Preference(**row) for row in snapshot["seeds"]),
                         tags=tuple(Preference(**row) for row in snapshot["tags"]),
                         required_tags=tuple(snapshot["required_tags"]), forbidden_tags=tuple(snapshot["forbidden_tags"]),
                         disliked_tags=tuple(snapshot["disliked_tags"]),
                         excluded_ids=tuple(row["release_group_id"] for row in exclusions), mode=snapshot["mode"])


class RecallStage:
    def __init__(self, snapshot: Path, *, binding: dict, weights: WeightConfig,
                 source_dependencies: tuple[str, ...] = (), engine: RecallEngine | None = None) -> None:
        validate_definition("RecallBinding", binding)
        if binding["weights_digest"] != digest(asdict(weights)):
            raise ContractError("BINDING_MISMATCH")
        self.snapshot = Path(snapshot)
        self.binding = deepcopy(binding)
        self.weights = weights
        self.source_dependencies = tuple(source_dependencies)
        self.engine = engine

    def _compute(self, request: RecallRequest):
        if self.engine is None:
            try:
                self.engine = load_a_v2(self.snapshot, weights=self.weights)
            except (OSError, ValueError, KeyError, TypeError, MemoryError):
                return "UNAVAILABLE", "artifact_unavailable", None
        try:
            result = self.engine.recall(request)
        except ValueError:
            return "UNSUPPORTED_INPUT", "unsupported_input", None
        except TimeoutError:
            return "TIMED_OUT", "recall_timed_out", None
        except (OSError, MemoryError):
            return "UNAVAILABLE", "recall_unavailable", None
        if (result.dataset_id != self.binding["data_snapshot_id"]
                or result.window != self.binding["window"] or result.algorithm != "A-V2"):
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
        if execution.start["start_mode"] == "CONTINUE" or execution.start["operation"] == "CURATED_REVIEWS":
            raise ContractError("RECALL_NOT_ALLOWED")
        require_binding(self.binding, execution.start["recall_binding"])
        require_binding(asdict(self.weights), execution.start["agent_policy"]["recall"]["weights"])
        if execution.start["input"]["accepted_preferences"] is None:
            raise ContractError("MISSING_PREFERENCES")
        require_binding(preferences, execution.start["input"]["accepted_preferences"])
        request = recall_request(preferences, execution.start["input"]["exclusions"], execution.start["selection_config"]["recall_target_count"])
        execution.phase = "RECALLING"
        if not preferences["has_positive_signal"]:
            status, reason, result = "UNSUPPORTED_INPUT", "unsupported_positive_input", None
        else:
            # SciPy CPU calls cannot be killed safely. Drain before slot release;
            # never allow a cancelled thread to keep using the next round's slot.
            worker = asyncio.create_task(asyncio.to_thread(self._compute, request))
            try:
                status, reason, result = await asyncio.wait_for(asyncio.shield(worker), execution.start["agent_policy"]["timing"]["recall_timeout_ms"] / 1000)
            except asyncio.TimeoutError:
                await _drain_worker(worker)
                status, reason, result = "TIMED_OUT", "recall_timed_out", None
            except asyncio.CancelledError:
                await _drain_worker(worker, cancelled=True)
        execution.ensure_active()
        candidates = [] if result is None else [{"release_group_id": row.album_id, "recall_rank": rank} for rank, row in enumerate(result.candidates, 1)]
        proposal = {"recall_binding": deepcopy(self.binding), "input_digest": preferences["input_digest"],
                    "effective_preference_digest": preferences["effective_preference_digest"],
                    "exclusion_digest": execution.start["input"]["exclusion_digest"], "requested_count": request.limit,
                    "returned_count": len(candidates), "execution_status": status, "raw_reason": reason,
                    "shortfall": request.limit - len(candidates), "truncated": False, "candidates": candidates,
                    "source_dependencies": list(self.source_dependencies)}
        proposal["proposal_digest"] = content_digest(proposal, "proposal_digest")
        validate_definition("RecallProposal", proposal)
        return proposal
