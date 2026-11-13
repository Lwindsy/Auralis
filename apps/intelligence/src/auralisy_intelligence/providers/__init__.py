"""Single-provider, controlled model and web adapters."""

from .gateway import ModelGateway
from .openai_responses import OpenAIResponsesAdapter
from .types import ModelBinding, ModelRequest, ReadResult, SearchHit
from .web import AiohttpPageFetcher, ToolGateway

__all__ = [
    "AiohttpPageFetcher",
    "ModelBinding",
    "ModelGateway",
    "ModelRequest",
    "OpenAIResponsesAdapter",
    "ReadResult",
    "SearchHit",
    "ToolGateway",
]
