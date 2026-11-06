"""Stage-facing gateways; every paid operation runs through Execution.invoke."""

import json
from typing import Any

from auralis_intelligence.agents import CallDenied, CallResult, Execution
from auralis_intelligence.contracts import require_binding
from .types import ModelAdapter, ModelBinding, ModelRequest


class ModelGateway:
    def __init__(self, adapter: ModelAdapter, bindings: dict[str, ModelBinding]) -> None:
        self.adapter = adapter
        self.bindings = dict(bindings)

    def binding(self, execution: Execution, role: str) -> ModelBinding:
        expected = execution.start["model_bindings"][role]
        actual = self.bindings.get(expected["ref"])
        if actual is None:
            raise CallDenied("MODEL_BINDING_UNKNOWN")
        require_binding(actual.wire(), expected)
        return actual

    async def generate(self, execution: Execution, *, role: str, purpose: str, system: str, input: Any,
                       output_schema: dict[str, Any], prompt_version: str, candidate_id: str | None = None,
                       read_ids: tuple[str, ...] = (), lane: str = "FOREGROUND", priority: int = 0) -> CallResult:
        binding = self.binding(execution, role)
        self.adapter.check_binding(binding)
        request = ModelRequest(system, input, prompt_version.replace(".", "_"), output_schema, binding.max_output_tokens)
        # Text-only inputs: UTF-8 byte count plus framing is a conservative token bound.
        material = {"system": system, "input": input, "schema": output_schema}
        token_bound = len(json.dumps(material, ensure_ascii=False).encode("utf-8")) + 256
        if token_bound > binding.max_input_tokens:
            raise CallDenied("MODEL_INPUT_TOO_LARGE")
        plan = execution.invocation_plan(purpose, role, material, candidate_id=candidate_id, read_ids=read_ids, lane=lane,
                                         max_input_tokens=token_bound, max_output_tokens=binding.max_output_tokens)
        timing = execution.start["agent_policy"]["timing"]
        timeout_key = {"interpret": "interpret_timeout_ms", "screen": "research_timeout_fg_ms", "select": "select_tech_timeout_ms", "write": "long_review_timeout_ms" if purpose == "WRITE_LONG" else "short_review_tech_timeout_ms"}[role]
        return await execution.invoke(plan, lambda: self.adapter.complete(binding, request), timeout_seconds=timing[timeout_key] / 1000, priority=priority)
