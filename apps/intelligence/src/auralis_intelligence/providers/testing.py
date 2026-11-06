"""Deterministic adapters for offline stage tests; no network or API keys."""

from collections import deque
from typing import Callable

from auralis_intelligence.agents import CallResult, Usage
from .types import ModelBinding, ModelRequest, SearchHit


class ScriptedModelAdapter:
    def __init__(self, replies: list[CallResult] | Callable[[ModelBinding, ModelRequest], CallResult]) -> None:
        self.replies = replies if callable(replies) else deque(replies)
        self.requests: list[tuple[str, ModelRequest]] = []

    def check_binding(self, binding: ModelBinding, *, search: bool = False) -> None:
        if not binding.structured_outputs:
            raise ValueError("UNSUPPORTED_STRUCTURED_OUTPUT")

    async def complete(self, binding: ModelBinding, request: ModelRequest) -> CallResult:
        self.requests.append((binding.ref, request))
        if callable(self.replies):
            return self.replies(binding, request)
        if not self.replies:
            raise AssertionError("NO_SCRIPTED_MODEL_REPLY")
        return self.replies.popleft()

    def clear(self) -> None:
        self.requests.clear()


class StaticSearchAdapter:
    def __init__(self, hits: tuple[SearchHit, ...]) -> None:
        self.hits = hits

    def check_binding(self, binding: ModelBinding, *, search: bool = False) -> None:
        if not binding.web_search:
            raise ValueError("UNSUPPORTED_WEB_SEARCH")

    async def search(self, binding: ModelBinding, query: str, allowed_domains: tuple[str, ...], blocked_domains: tuple[str, ...]) -> CallResult:
        return CallResult(self.hits, usage=Usage(1, 1, 0))
