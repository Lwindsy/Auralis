"""One revision of branch and genre material, explicitly supplied by the caller."""

import json
import unicodedata
from copy import deepcopy
from pathlib import Path

from auralisy_intelligence.contracts import digest
from auralisy_intelligence.genre_taxonomy import load_taxonomy, taxonomy_hash


class PreferenceError(ValueError):
    """Safe failure code and outcome, never containing user or source text."""

    def __init__(
        self, code: str, status: str = "FAILED", *, spans: tuple[dict[str, int], ...] = ()
    ) -> None:
        super().__init__(code)
        self.code = code
        self.status = status
        self.spans = tuple(dict(span) for span in spans)


def _normalized_name(name: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", name).casefold().split())


class ReferenceCatalog:
    def __init__(
        self, document: dict, *, expected_revision: str | None = None, taxonomy: dict | None = None
    ) -> None:
        self.revision = digest(document)
        if expected_revision is not None and expected_revision != self.revision:
            raise PreferenceError("REFERENCE_VERSION_MISMATCH")
        try:
            self.genres = self._index(document["genres"])
            self.branches = self._index(document["branches"])
            for branch in self.branches.values():
                ids = branch["genre_ids"]
                if not isinstance(ids, list) or any(
                    identifier not in self.genres for identifier in ids
                ):
                    raise ValueError
            self._branch_genres = {
                identifier: branch["genre_ids"] for identifier, branch in self.branches.items()
            }
            self._has_taxonomy = taxonomy is not None
            if taxonomy is not None:
                self._branch_genres = self._direct_members(taxonomy)
            elif "taxonomy_sha256" in document:
                self._branch_genres = None
            self._genre_names: dict[str, set[str]] = {}
            for identifier, row in self.genres.items():
                for label in (row["name"], *row["aliases"]):
                    key = _normalized_name(label)
                    if key:
                        self._genre_names.setdefault(key, set()).add(identifier)
        except (KeyError, TypeError, ValueError):
            raise PreferenceError("REFERENCE_UNAVAILABLE") from None

    def _direct_members(self, taxonomy: dict) -> dict[str, list[str]]:
        nodes = {row["id"]: row for row in taxonomy["genres"]}
        result = {}
        for identifier, branch in self.branches.items():
            name = _normalized_name(branch["name"])
            roots = [
                genre_id
                for genre_id in branch["genre_ids"]
                if _normalized_name(nodes[genre_id]["name"]) == name
            ]
            if len(roots) != 1:
                raise ValueError("BRANCH_ROOT_NOT_UNIQUE")
            result[identifier] = [
                genre_id
                for genre_id in branch["genre_ids"]
                if roots[0] in nodes[genre_id]["parents"]
            ]
        return result

    @staticmethod
    def _index(rows: list[dict]) -> dict[str, dict]:
        result = {}
        for row in rows:
            row = deepcopy(row)
            # DATA-029 records its known missing descriptions as JSON null.
            if row.get("description") is None:
                row["description"] = ""
            if row["id"] in result or any(
                not isinstance(row[key], str) or not row[key].strip() for key in ("id", "name")
            ):
                raise ValueError
            if (
                not isinstance(row["description"], str)
                or not isinstance(row["aliases"], list)
                or any(not isinstance(alias, str) for alias in row["aliases"])
            ):
                raise ValueError
            result[row["id"]] = row
        return result

    @classmethod
    def load(
        cls, path: Path, *, expected_revision: str | None = None, taxonomy_path: Path | None = None
    ) -> "ReferenceCatalog":
        try:
            document = json.loads(Path(path).read_text(encoding="utf-8"))
            taxonomy = load_taxonomy(taxonomy_path) if taxonomy_path is not None else None
            if taxonomy_path is not None and document.get("taxonomy_sha256") != taxonomy_hash(
                taxonomy_path
            ):
                raise PreferenceError("REFERENCE_VERSION_MISMATCH")
        except PreferenceError:
            raise
        except (OSError, ValueError):
            raise PreferenceError("REFERENCE_UNAVAILABLE") from None
        return cls(document, expected_revision=expected_revision, taxonomy=taxonomy)

    def named_genre_id(self, name: str) -> str | None:
        """Resolve a stated name or alias only when the catalog match is unique."""
        identifiers = self._genre_names.get(_normalized_name(name), set())
        return next(iter(identifiers)) if len(identifiers) == 1 else None

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
            for row in self._genre_material(identifier):
                result.setdefault(row["id"], row)
        if not result and not self._has_taxonomy:
            raise PreferenceError("REFERENCE_UNAVAILABLE")
        return list(result.values())

    def expand_limited(self, identifiers: list[str], limit: int, request_text: str) -> list[dict]:
        if limit < 1:
            raise ValueError("GENRE_LIMIT_MUST_BE_POSITIVE")
        expanded = self.expand(identifiers)
        if len(expanded) <= limit:
            return expanded
        text = request_text.casefold()
        explicit = []
        for row in expanded:
            labels = [row["name"], *row["aliases"]]
            if any(label.strip() and label.casefold() in text for label in labels):
                explicit.append(row)
        chosen = {row["id"]: row for row in explicit[:limit]}
        branch_rows = [self._genre_material(identifier) for identifier in identifiers]
        cursor = 0
        while len(chosen) < limit and any(cursor < len(rows) for rows in branch_rows):
            for rows in branch_rows:
                if cursor < len(rows):
                    chosen.setdefault(rows[cursor]["id"], rows[cursor])
                    if len(chosen) == limit:
                        break
            cursor += 1
        return list(chosen.values())

    def _genre_material(self, branch_id: str) -> list[dict]:
        if self._branch_genres is None:
            raise PreferenceError("REFERENCE_UNAVAILABLE")
        rows = (self.genres[identifier] for identifier in self._branch_genres[branch_id])
        return [
            self.material(row) for row in rows if self._has_taxonomy or row["description"].strip()
        ]


def numbered_choices(rows: list[dict]) -> tuple[list[dict], dict[int, str]]:
    """Expose request-local numbers to the model and keep catalog IDs in Python."""
    choices = []
    ids = {}
    for number, row in enumerate(rows, start=1):
        choices.append(
            {"number": number, **{key: row[key] for key in ("name", "aliases", "description")}}
        )
        ids[number] = row["id"]
    return choices, ids


def locate(text: str, quote: str) -> dict[str, int]:
    """Unicode code points, half-open; ambiguous repeated quotes are rejected."""
    start = text.find(quote)
    if not quote or start < 0 or text.find(quote, start + 1) >= 0:
        raise PreferenceError("INTERPRETATION_INVALID_SPAN")
    return {"start": start, "end": start + len(quote)}
