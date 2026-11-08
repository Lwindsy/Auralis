"""Read explicitly supplied recall snapshots and structured requests."""

import json
from dataclasses import fields
from pathlib import Path

import numpy as np
from scipy import sparse

from .common import dataset_fingerprint, validate_dataset
from .types import Preference, RecallDataset, RecallRequest


def read_json(path: Path) -> dict:
    with Path(path).open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError("JSON root must be an object")
    return value


def write_json(path: Path, value: dict) -> None:
    with Path(path).open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def load_request(path: Path) -> RecallRequest:
    raw = read_json(path)
    limit = raw.get("limit")
    if isinstance(limit, float) and limit.is_integer():
        raw["limit"] = int(limit)
    unknown = set(raw) - {field.name for field in fields(RecallRequest)}
    if unknown:
        raise ValueError(f"unknown request fields: {sorted(unknown)}; raw text is not supported")
    for key in ("seeds", "tags"):
        if key in raw:
            if not isinstance(raw[key], list) or any(not isinstance(x, dict) for x in raw[key]):
                raise ValueError(f"{key} must be a list of structured preferences")
            try:
                raw[key] = tuple(Preference(**item) for item in raw[key])
            except TypeError as exc:
                raise ValueError(f"invalid {key} preference fields") from exc
    for key in ("disliked_tags", "forbidden_tags", "required_tags", "excluded_ids"):
        if key in raw:
            if not isinstance(raw[key], list):
                raise ValueError(f"{key} must be a list")
            raw[key] = tuple(raw[key])
    try:
        return RecallRequest(**raw)
    except TypeError as exc:
        raise ValueError("invalid recall request fields") from exc


def save_dataset(data: RecallDataset, path: Path, provenance: dict | None = None) -> None:
    validate_dataset(data)
    path = Path(path)
    path.mkdir(parents=True, exist_ok=False)
    sparse.save_npz(path / "album_tags.npz", data.album_tags)
    sparse.save_npz(path / "interactions.npz", data.interactions)
    write_json(
        path / "dataset.json",
        {
            "format_version": 1,
            "dataset_id": data.dataset_id,
            "window": data.window,
            "album_ids": list(data.album_ids),
            "tag_ids": list(data.tag_ids),
            "tag_parents": list(data.tag_parents),
            "fingerprint": dataset_fingerprint(data),
            "provenance": provenance or {},
        },
    )


def load_dataset(path: Path) -> RecallDataset:
    """Load a sparse directory or a small, explicit synthetic JSON fixture."""
    path = Path(path)
    raw = read_json(path / "dataset.json" if path.is_dir() else path)
    if raw.get("format_version") != 1:
        raise ValueError("unsupported dataset format_version")
    albums, tags = tuple(raw["album_ids"]), tuple(raw["tag_ids"])
    if path.is_dir():
        album_tags = sparse.load_npz(path / "album_tags.npz")
        interactions = sparse.load_npz(path / "interactions.npz")
    else:
        # JSON is intended for small fixtures; real runs use sparse NPZ files.
        album_tags = sparse.csr_matrix(np.asarray(raw["album_tags"], dtype=float))
        interactions = sparse.csr_matrix(np.asarray(raw["interactions"], dtype=float))
    data = RecallDataset(
        albums,
        tags,
        album_tags,
        interactions,
        raw["dataset_id"],
        raw["window"],
        tuple(tuple(pair) for pair in raw.get("tag_parents", [])),
    )
    validate_dataset(data)
    if path.is_dir() and raw.get("fingerprint") != dataset_fingerprint(data):
        raise ValueError("dataset fingerprint mismatch")
    return data
