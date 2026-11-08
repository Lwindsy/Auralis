"""Audience export boundaries and independent source reconciliation."""

import json
from dataclasses import asdict
from uuid import UUID

import numpy as np
import pytest
from auralis_intelligence.contracts import content_digest, digest
from auralis_intelligence.recall import WeightConfig, load_a_v2
from auralis_intelligence.recall.audience import build_snapshot, check_engine, main, verify_snapshot
from auralis_intelligence.recall.common import dataset_fingerprint
from auralis_intelligence.recall.io import save_dataset, write_json
from auralis_intelligence.recall.types import RecallDataset
from scipy import sparse


@pytest.fixture
def source(tmp_path):
    ids = tuple(str(UUID(int=i)) for i in (3, 1, 2))
    # Duplicate listener/album coordinates represent repeated plays, not people.
    rows = list(range(49)) + list(range(50)) + [0, 0]
    cols = [0] * 49 + [1] * 50 + [0, 2]
    values = [7] * 99 + [100, 0]
    data = RecallDataset(
        ids,
        ("rock",),
        sparse.csr_matrix(np.ones((3, 1))),
        sparse.coo_matrix((values, (rows, cols)), shape=(51, 3)),
        "train",
        "0",
    )
    path = tmp_path / "train"
    save_dataset(data, path)
    binding = {
        "strategy_revision": "A-V2",
        "data_snapshot_id": "train",
        "window": "0",
        "weights_digest": digest(asdict(WeightConfig())),
    }
    return path, binding, dataset_fingerprint(data)


def test_counts_match_public_engine_and_unknown_stays_unknown(source):
    path, binding, fingerprint = source
    result = build_snapshot(path, binding, fingerprint)
    counts = {row["release_group_id"]: row["listeners"] for row in result["counts"]}
    assert list(counts.values()) == [50, 0, 49]
    engine = load_a_v2(path, weights=WeightConfig())
    observed = engine.training_audience([*counts, "missing"])
    assert observed["sample_listeners"] == result["sample_listeners"] == 51
    assert observed["counts"] == {**counts, "missing": None}
    assert result["window"] == observed["window"] == "0"
    assert check_engine(path, result, WeightConfig())["sample_listeners"] == 51
    with pytest.raises(ValueError, match="weights"):
        check_engine(path, result, WeightConfig(special=3.0))


@pytest.mark.parametrize(
    "field,value",
    [("data_snapshot_id", "other"), ("window", "2025"), ("strategy_revision", "C-V0")],
)
def test_wrong_binding(source, field, value):
    path, binding, fingerprint = source
    with pytest.raises(ValueError):
        build_snapshot(path, {**binding, field: value}, fingerprint)


def test_missing_source_and_wrong_fingerprint(source):
    path, binding, fingerprint = source
    with pytest.raises(FileNotFoundError):
        build_snapshot(path / "missing", binding, fingerprint)
    with pytest.raises(ValueError, match="fingerprint"):
        build_snapshot(path, binding, "0" * 64)


@pytest.mark.parametrize("mutation", ["count", "missing", "zero", "weights", "fingerprint"])
def test_self_consistent_tampering_rejected(source, tmp_path, mutation):
    path, binding, fingerprint = source
    expected = build_snapshot(path, binding, fingerprint)
    actual = json.loads(json.dumps(expected))
    if mutation == "count":
        actual["counts"][0]["listeners"] = 49
    elif mutation == "missing":
        actual["counts"].pop()
    elif mutation == "zero":
        actual["counts"].pop(1)
    elif mutation == "weights":
        actual["recall_binding"]["weights_digest"] = "sha256:" + "0" * 64
    else:
        actual["snapshot_fingerprint"] = "0" * 64
    actual["digest"] = content_digest(actual, "digest")
    artifact = tmp_path / "audience.json"
    write_json(artifact, actual)
    with pytest.raises(ValueError):
        verify_snapshot(artifact, expected)


def test_cli_export_readonly_verify_and_no_overwrite(source, tmp_path, capsys):
    path, binding, fingerprint = source
    binding_file, artifact = tmp_path / "binding.json", tmp_path / "audience.json"
    write_json(binding_file, binding)
    args = [
        "--snapshot",
        str(path),
        "--binding",
        str(binding_file),
        "--expected-fingerprint",
        fingerprint,
        "--artifact",
        str(artifact),
    ]
    main(["export", *args])
    receipt = json.loads(capsys.readouterr().out)
    assert receipt["reconciled_rows"] == 3
    assert receipt["eligible_rows"] == 1
    original = artifact.read_bytes()
    main(["verify", *args])
    assert json.loads(capsys.readouterr().out)["file_digest"] == receipt["file_digest"]
    assert artifact.read_bytes() == original
    with pytest.raises(FileExistsError):
        main(["export", *args])
