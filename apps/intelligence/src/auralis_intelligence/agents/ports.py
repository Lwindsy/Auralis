"""Execution ports: no HTTP, provider SDK, credentials or persistent storage."""

from dataclasses import dataclass
from typing import Any, Protocol


class ControlUnavailable(RuntimeError):
    """The control operation has no usable response; mutation may have happened."""


class DeliveryPending(ControlUnavailable):
    def __init__(self, delivery_id: str) -> None:
        super().__init__("DELIVERY_PENDING")
        self.delivery_id = delivery_id


class CallDenied(RuntimeError):
    """A safe denial code, with no prompt or source content."""


@dataclass(frozen=True)
class Usage:
    input_tokens: int | None = None
    output_tokens: int | None = None
    cached_input_tokens: int | None = None


@dataclass(frozen=True)
class CallResult:
    output: Any = None
    outcome: str = "COMPLETED"
    finish: str = "COMPLETE"
    usage: Usage = Usage()
    provider_request_ref: str | None = None
    invocation_id: str | None = None
    provider_http_status: int | None = None
    service_tier: str | None = None
    diagnostic: dict[str, Any] | None = None


class ControlPort(Protocol):
    async def request(self, execution_id: str, operation: str, payload: dict[str, Any]) -> dict[str, Any]: ...


class Stage(Protocol):
    async def __call__(self, execution: Any) -> None: ...
