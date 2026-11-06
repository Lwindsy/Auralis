"""One album's execution-local evidence and accepted screening outcome."""

from dataclasses import dataclass, field
from typing import Any

from auralis_intelligence.providers.types import ReadResult


@dataclass
class ReadRecord:
    ref: str
    result: ReadResult
    excerpt: str = field(default="", repr=False)
    deterministic_pass: bool = False
    rejection: str | None = None

    def clear(self) -> None:
        self.result.clear()
        self.excerpt = ""


@dataclass
class AlbumDossier:
    candidate_id: str
    identity: dict[str, Any]
    reads: list[ReadRecord] = field(default_factory=list)
    screening: dict[str, Any] | None = None

    @property
    def qualified(self) -> list[ReadRecord]:
        ids = set((self.screening or {}).get("qualified_read_ids", []))
        return [record for record in self.reads if record.result.receipt["read_id"] in ids]

    def clear(self) -> None:
        for record in self.reads:
            record.clear()


@dataclass(frozen=True)
class ResearchResult:
    screening: dict[str, Any]
    dossier: AlbumDossier
    eligible: bool
    error_code: str | None = None
