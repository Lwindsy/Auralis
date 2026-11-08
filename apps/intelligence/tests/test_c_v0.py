import json
import os
import subprocess
import sys

import numpy as np
import pytest
from auralis_intelligence.recall import WeightConfig, load_c_v0
from auralis_intelligence.recall.common import dataset_fingerprint
from auralis_intelligence.recall.io import load_dataset
from test_recall import APP, FIXTURES, SNAPSHOT


@pytest.fixture
def artifact(tmp_path):
    data = load_dataset(SNAPSHOT)
    # Synthetic vectors exercise inference and never leave the test directory.
    model = tmp_path / "model.npz"
    config = {
        "dimensions": 32,
        "layers": 2,
        "epochs": 5,
        "query_epochs": 5,
        "steps_per_epoch": 73,
        "batch_size": 1024,
        "learning_rate": 0.01,
        "regularization": 0.0001,
        "max_support": 10,
        "seed": 17,
        "device": "cuda",
    }
    items = np.zeros((8, 32), dtype=np.float32)
    items[:3, 0] = [1, 3, 2]
    np.savez(
        model,
        metadata=json.dumps(
            {
                "format": 1,
                "algorithm": "lightgcn",
                "config": config,
                "fingerprint": dataset_fingerprint(data),
            }
        ),
        item_embeddings=items,
        seed_projection=np.eye(32, dtype=np.float32),
        tag_embeddings=np.zeros((4, 32), dtype=np.float32),
        item_coverage=np.ones(8, dtype=bool),
        tag_coverage=np.ones(4, dtype=bool),
    )
    return model


def test_c_v0_real_cli_boundary(artifact):
    run = subprocess.run(
        [
            sys.executable,
            "-m",
            "auralis_intelligence.recall",
            "--snapshot",
            str(SNAPSHOT),
            "--request",
            str(FIXTURES / "request.json"),
            "--special-weight",
            "2",
            "--focus-weight",
            "2",
            "--c-v0-model",
            str(artifact),
        ],
        env={**os.environ, "PYTHONPATH": str(APP / "src")},
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert run.returncode == 0, run.stderr
    result = json.loads(run.stdout)
    assert result["algorithm"] == "C-V0"
    assert result["candidate_ids"] == ["a1", "a2"]
    assert result["returned_count"] == 2


def test_c_v0_rejects_mismatched_snapshot(artifact, tmp_path):
    changed = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    changed["dataset_id"] = "other-snapshot"
    path = tmp_path / "other.json"
    path.write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(ValueError, match="does not match"):
        load_c_v0(path, artifact, weights=WeightConfig(2, 2))
