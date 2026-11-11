"""Stage-facing gateways; every paid operation runs through Execution.invoke."""

import json
import logging
from dataclasses import asdict
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from typing import Any

from auralis_intelligence.agents import CallDenied, CallResult, Execution
from auralis_intelligence.contracts import require_binding

from .types import ModelAdapter, ModelBinding, ModelRequest


class ModelGateway:
    def __init__(
        self,
        adapter: ModelAdapter,
        bindings: dict[str, ModelBinding],
        *,
        trial_trace_directory: Path | None = None,
    ) -> None:
        self.adapter = adapter
        self.bindings = dict(bindings)
        self.trial_trace_directory = trial_trace_directory
        if trial_trace_directory is not None:
            trial_trace_directory.mkdir(parents=True, exist_ok=True)

    def _trace(self, execution, plan, event, **fields):
        if self.trial_trace_directory is None:
            return
        record = {
            "format": "auralis-model-trial.v1",
            "event": event,
            "recorded_at": datetime.now(timezone.utc).isoformat(),
            "session_id": execution.start["session_id"],
            "round_id": execution.start["round_id"],
            "run_id": execution.start["run_id"],
            "execution_id": execution.id,
            "invocation_id": plan["invocation_id"],
            **fields,
        }
        name = sha256(plan["invocation_id"].encode()).hexdigest() + ".jsonl"
        with (self.trial_trace_directory / name).open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")

    def binding(self, execution: Execution, role: str) -> ModelBinding:
        expected = execution.start["model_bindings"][role]
        actual = self.bindings.get(expected["ref"])
        if actual is None:
            raise CallDenied("MODEL_BINDING_UNKNOWN")
        require_binding(actual.wire(), expected)
        return actual

    async def generate(
        self,
        execution: Execution,
        *,
        role: str,
        purpose: str,
        system: str,
        input: Any,
        output_schema: dict[str, Any],
        prompt_version: str,
        candidate_id: str | None = None,
        read_ids: tuple[str, ...] = (),
        lane: str = "FOREGROUND",
        priority: int = 0,
        timeout_ms: int | None = None,
    ) -> CallResult:
        binding = self.binding(execution, role)
        self.adapter.check_binding(binding)
        if lane == "BACKGROUND" and priority == 0:
            priority = 4
        request = ModelRequest(
            system,
            input,
            prompt_version.replace(".", "_"),
            output_schema,
            binding.max_output_tokens,
        )
        # Text-only inputs: UTF-8 byte count plus framing is a conservative token bound.
        material = {"system": system, "input": input, "schema": output_schema}
        token_bound = len(json.dumps(material, ensure_ascii=False).encode("utf-8")) + 256
        if token_bound > binding.max_input_tokens:
            raise CallDenied("MODEL_INPUT_TOO_LARGE")
        plan = execution.invocation_plan(
            purpose,
            role,
            material,
            candidate_id=candidate_id,
            read_ids=read_ids,
            lane=lane,
            max_input_tokens=token_bound,
            max_output_tokens=binding.max_output_tokens,
        )
        timing = execution.start["agent_policy"]["timing"]
        timeout_key = {
            "long_write": "long_review_timeout_ms",
            "interpret": "interpret_timeout_ms",
            "screen": "research_timeout_fg_ms",
            "select": "select_tech_timeout_ms",
            "write": "long_review_timeout_ms"
            if purpose == "WRITE_LONG"
            else "short_review_tech_timeout_ms",
        }[role]
        self._trace(
            execution,
            plan,
            "prepared",
            prompt_version=prompt_version,
            model_binding=binding.wire(),
            purpose=purpose,
            candidate_id=candidate_id,
            read_ids=list(read_ids),
            request=material,
        )
        try:
            result = await execution.invoke(
                plan,
                lambda: self.adapter.complete(binding, request),
                timeout_seconds=(timeout_ms or timing[timeout_key]) / 1000,
                priority=priority,
            )
        except BaseException as error:
            self._trace(execution, plan, "aborted", error_class=type(error).__name__)
            raise
        self._trace(
            execution,
            plan,
            "result",
            outcome=result.outcome,
            finish=result.finish,
            output=result.output,
            usage=asdict(result.usage),
        )
        if result.finish != "COMPLETE":
            detail = result.diagnostic or {"category": "MODEL_FAILED"}
            logging.getLogger(__name__).warning(
                "AURALIS_MODEL_DIAGNOSTIC %s",
                json.dumps(
                    {
                        "invocation_id": plan["invocation_id"],
                        "purpose": purpose,
                        "finish": result.finish,
                        "outcome": result.outcome,
                        "http_status": result.provider_http_status,
                        **{
                            k: detail[k]
                            for k in (
                                "category",
                                "validator",
                                "schema_path",
                                "schema_errors",
                                "line",
                                "column",
                                "output_text_chars",
                            )
                            if k in detail
                        },
                    },
                    ensure_ascii=True,
                ),
            )
        return result
