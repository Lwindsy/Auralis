"""Provider-neutral model and web types, shared by the actual stage consumers."""

from dataclasses import dataclass, field
from typing import Any, Protocol

from auralis_intelligence.agents.ports import CallResult


@dataclass(frozen=True)
class ModelBinding:
    ref: str
    revision: str
    model: str
    price_revision: str
    max_input_tokens: int
    max_output_tokens: int
    structured_outputs: bool = True
    web_search: bool = False
    live_enabled: bool = False
    price_source: str | None = None
    input_microusd_per_million: int | None = None
    cached_input_microusd_per_million: int | None = None
    output_microusd_per_million: int | None = None
    search_microusd_per_call: int | None = None

    def wire(self) -> dict[str, Any]:
        return {name: getattr(self, name) for name in ("ref", "revision", "model", "price_revision", "max_input_tokens", "max_output_tokens")}


@dataclass(frozen=True)
class ModelRequest:
    system: str
    input: Any
    schema_name: str
    output_schema: dict[str, Any]
    max_output_tokens: int


@dataclass(frozen=True)
class SearchHit:
    url: str
    title: str
    snippet: str = ""


@dataclass
class ReadResult:
    receipt: dict[str, Any]
    text: str = field(default="", repr=False)

    def clear(self) -> None:
        self.text = ""


class ModelAdapter(Protocol):
    def check_binding(self, binding: ModelBinding, *, search: bool = False) -> None: ...
    async def complete(self, binding: ModelBinding, request: ModelRequest) -> CallResult: ...


class SearchAdapter(Protocol):
    def check_binding(self, binding: ModelBinding, *, search: bool = False) -> None: ...
    async def search(self, binding: ModelBinding, query: str, allowed_domains: tuple[str, ...], blocked_domains: tuple[str, ...]) -> CallResult: ...
