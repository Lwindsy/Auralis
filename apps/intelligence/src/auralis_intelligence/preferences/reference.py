"""One revision of branch and genre material, explicitly supplied by the caller."""

from copy import deepcopy
import json
from pathlib import Path

from auralis_intelligence.contracts import digest


class PreferenceError(ValueError):
    """Safe failure code and outcome, never containing user or source text."""

    def __init__(self, code: str, status: str = "FAILED", *, spans: tuple[dict[str, int], ...] = ()) -> None:
        super().__init__(code)
        self.code = code
        self.status = status
        self.spans = tuple(dict(span) for span in spans)


class ReferenceCatalog:
    def __init__(self, document: dict, *, expected_revision: str | None = None) -> None:
        self.revision = digest(document)
        if expected_revision is not None and expected_revision != self.revision:
            raise PreferenceError("REFERENCE_VERSION_MISMATCH")
        try:
            self.genres = self._index(document["genres"])
            self.branches = self._index(document["branches"])
            for branch in self.branches.values():
                ids = branch["genre_ids"]
                if not isinstance(ids, list) or any(identifier not in self.genres for identifier in ids):
                    raise ValueError
        except (KeyError, TypeError, ValueError):
            raise PreferenceError("REFERENCE_UNAVAILABLE") from None

    @staticmethod
    def _index(rows: list[dict]) -> dict[str, dict]:
        result = {}
        for row in rows:
            row = deepcopy(row)
            # DATA-029 records its known missing descriptions as JSON null.
            if row.get("description") is None:
                row["description"] = ""
            if row["id"] in result or any(not isinstance(row[key], str) or not row[key].strip() for key in ("id", "name")):
                raise ValueError
            if not isinstance(row["description"], str) or not isinstance(row["aliases"], list) or any(not isinstance(alias, str) for alias in row["aliases"]):
                raise ValueError
            result[row["id"]] = row
        return result

    @classmethod
    def load(cls, path: Path, *, expected_revision: str | None = None) -> "ReferenceCatalog":
        try:
            document = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise PreferenceError("REFERENCE_UNAVAILABLE") from None
        return cls(document, expected_revision=expected_revision)

    @staticmethod
    def material(row: dict) -> dict:
        return {key: deepcopy(row[key]) for key in ("id", "name", "aliases", "description")}

    def branch_material(self) -> list[dict]:
        rows = [self.material(row) for row in self.branches.values() if row["description"].strip()]
        if not rows:
            raise PreferenceError("REFERENCE_UNAVAILABLE")
        return rows

    def expand(self, identifiers: list[str]) -> list[dict]:
        result = {}
        for identifier in identifiers:
            if identifier not in self.branches:
                raise PreferenceError("INTERPRETATION_INVALID_ID")
            for genre_id in self.branches[identifier]["genre_ids"]:
                row = self.genres[genre_id]
                # Missing descriptions are known reference gaps, not invented material.
                if row["description"].strip():
                    result.setdefault(genre_id, self.material(row))
        if not result:
            raise PreferenceError("REFERENCE_UNAVAILABLE")
        return list(result.values())


def locate(text: str, quote: str) -> dict[str, int]:
    """Unicode code points, half-open; ambiguous repeated quotes are rejected."""
    start = text.find(quote)
    if not quote or start < 0 or text.find(quote, start + 1) >= 0:
        raise PreferenceError("INTERPRETATION_INVALID_SPAN")
    return {"start": start, "end": start + len(quote)}
