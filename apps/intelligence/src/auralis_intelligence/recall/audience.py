"""Export and reconcile the frozen AudienceSnapshot against an explicit dataset."""

import argparse
import hashlib
import json
from dataclasses import asdict
from pathlib import Path

from auralis_intelligence.contracts import (
    content_digest,
    digest,
    require_binding,
    validate_definition,
)

from . import WeightConfig, load_a_v2
from .common import dataset_fingerprint
from .io import load_dataset, read_json


def build_snapshot(snapshot: Path, binding: dict, fingerprint: str) -> dict:
    """Require an independently supplied binding and fingerprint before counting."""
    validate_definition("RecallBinding", binding)
    if binding["strategy_revision"] != "A-V2":
        raise ValueError("Audience export requires A-V2")
    data = load_dataset(snapshot)
    if dataset_fingerprint(data) != fingerprint:
        raise ValueError("expected snapshot fingerprint mismatch")
    require_binding(
        {"dataset_id": data.dataset_id, "window": data.window},
        {"dataset_id": binding["data_snapshot_id"], "window": binding["window"]},
    )
    # load_dataset canonicalizes duplicate coordinates and removes explicit zeros.
    # This is the same positive listener-row count used by RecallEngine.audience.
    counts = dict(zip(data.album_ids, map(int, data.interactions.getnnz(axis=0))))
    result = {
        "format_version": 1,
        "recall_binding": dict(binding),
        "snapshot_fingerprint": fingerprint,
        "source": "LISTENBRAINZ_SAMPLE",
        "dataset_id": data.dataset_id,
        "window": data.window,
        "sample_listeners": data.interactions.shape[0],
        "counts": [
            {"release_group_id": identifier, "listeners": counts[identifier]}
            for identifier in sorted(counts)
        ],
    }
    result["digest"] = content_digest(result, "digest")
    validate_definition("AudienceSnapshot", result)
    return result


def verify_snapshot(artifact: Path, expected: dict) -> dict:
    """Reconcile every catalog row, including zeros; never trust self-digests alone."""
    actual = read_json(artifact)
    validate_definition("AudienceSnapshot", actual)
    require_binding(actual, expected)
    return actual


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def source_digests(snapshot: Path) -> dict:
    paths = (
        [snapshot / name for name in ("dataset.json", "album_tags.npz", "interactions.npz")]
        if snapshot.is_dir()
        else [snapshot]
    )
    return {str(path.resolve()): file_digest(path) for path in paths}


def check_engine(snapshot: Path, actual: dict, weights: WeightConfig) -> dict:
    """Sample the public A-V2 port, including each present threshold boundary."""
    if digest(asdict(weights)) != actual["recall_binding"]["weights_digest"]:
        raise ValueError("engine weights binding mismatch")
    rows = actual["counts"]
    selected = {
        row["release_group_id"]: row["listeners"] for row in rows[:: max(1, len(rows) // 128)]
    }
    for boundary in (0, 49, 50):
        row = next((row for row in rows if row["listeners"] == boundary), None)
        if row is not None:
            selected[row["release_group_id"]] = row["listeners"]
    engine = load_a_v2(snapshot, weights=weights)
    observed = engine.audience([*selected, "data-031-unknown-id"])
    expected = {
        "dataset_id": actual["dataset_id"],
        "window": actual["window"],
        "sample_listeners": actual["sample_listeners"],
        "counts": {**selected, "data-031-unknown-id": None},
    }
    require_binding(observed, expected)
    return observed


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("export", "verify"))
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--binding", type=Path, required=True)
    parser.add_argument("--expected-fingerprint", required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument(
        "--engine-weights", type=Path, help="Explicit WeightConfig JSON for API sampling"
    )
    args = parser.parse_args(argv)
    sources = source_digests(args.snapshot)
    expected = build_snapshot(args.snapshot, read_json(args.binding), args.expected_fingerprint)
    if sources != source_digests(args.snapshot):
        raise ValueError("source changed during reconciliation")
    if args.operation == "export":
        # Exclusive creation protects existing artifacts and source files.
        with args.artifact.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(expected, stream, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
            stream.write("\n")
    actual = verify_snapshot(args.artifact, expected)
    engine_sample = (
        check_engine(args.snapshot, actual, WeightConfig(**read_json(args.engine_weights)))
        if args.engine_weights
        else None
    )
    counts = [row["listeners"] for row in actual["counts"]]
    print(
        json.dumps(
            {
                "operation": args.operation,
                "artifact": str(args.artifact.resolve()),
                "file_digest": file_digest(args.artifact),
                "digest": actual["digest"],
                "snapshot_fingerprint": actual["snapshot_fingerprint"],
                "recall_binding": actual["recall_binding"],
                "source_files": sources,
                "sample_listeners": actual["sample_listeners"],
                "catalog_rows": len(counts),
                "reconciled_rows": len(counts),
                "zero_rows": counts.count(0),
                "listeners_49_rows": counts.count(49),
                "listeners_50_rows": counts.count(50),
                "eligible_rows": sum(count >= 50 for count in counts),
                "listener_album_pairs": sum(counts),
                "engine_sample": engine_sample,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
