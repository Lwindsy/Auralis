"""Authoritative RYM genre graph shared with the catalog service."""

import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator


def load_taxonomy(path: Path) -> dict:
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    schema = json.loads(
        (Path(__file__).parent / "contracts/genre-taxonomy-v1.schema.json").read_text(
            encoding="utf-8"
        )
    )
    Draft202012Validator(schema).validate(document)
    nodes = {row["id"]: row for row in document["genres"]}
    if len(nodes) != len(document["genres"]):
        raise ValueError("DUPLICATE_TAXONOMY_ID")
    visited, active = set(), set()

    def visit(identifier):
        if identifier not in nodes:
            raise ValueError("TAXONOMY_PARENT_MISSING")
        if identifier in active:
            raise ValueError("TAXONOMY_CYCLE")
        if identifier in visited:
            return
        active.add(identifier)
        for parent in nodes[identifier]["parents"]:
            visit(parent)
        active.remove(identifier)
        visited.add(identifier)

    for identifier in nodes:
        visit(identifier)
    return document


def taxonomy_hash(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def check_artifacts(taxonomy_path: Path, reference_path: Path, snapshot_path: Path) -> None:
    """Reject mixed taxonomy/reference/recall generations before executing a request."""
    taxonomy = load_taxonomy(taxonomy_path)
    revision = taxonomy_hash(taxonomy_path)
    reference = json.loads(Path(reference_path).read_text(encoding="utf-8"))
    snapshot_path = Path(snapshot_path)
    snapshot = json.loads(
        (snapshot_path / "dataset.json" if snapshot_path.is_dir() else snapshot_path).read_text(
            encoding="utf-8"
        )
    )
    ids = {n["id"] for n in taxonomy["genres"]}
    edges = {(p, n["id"]) for n in taxonomy["genres"] for p in n["parents"]}
    if (
        reference.get("taxonomy_sha256") != revision
        or snapshot.get("provenance", {}).get("taxonomy_sha256") != revision
        or {n["id"] for n in reference["genres"]} != ids
        or set(snapshot["tag_ids"]) != ids
        or {tuple(edge) for edge in snapshot["tag_parents"]} != edges
    ):
        raise ValueError("GENRE_ARTIFACT_MISMATCH")
