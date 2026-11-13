"""One album's execution-local evidence and accepted screening outcome."""

from dataclasses import dataclass, field
from typing import Any

from auralisy_intelligence.providers.types import ReadResult


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

    def discard_reads(self, read_ids: set[str]) -> bool:
        affected = self.screening is not None and bool(
            set(self.screening["qualified_read_ids"]) & read_ids
        )
        for record in self.reads:
            if record.result.receipt["read_id"] in read_ids:
                record.clear()
                affected = True
        if affected and self.screening is not None:
            self.screening["qualified_read_ids"] = [
                identifier
                for identifier in self.screening["qualified_read_ids"]
                if identifier not in read_ids
            ]
            self.screening.update(disposition="INELIGIBLE", reason_code="READ_UNKNOWN", brief=None)
        return affected


@dataclass(frozen=True)
class ResearchResult:
    screening: dict[str, Any]
    dossier: AlbumDossier
    eligible: bool
    error_code: str | None = None
