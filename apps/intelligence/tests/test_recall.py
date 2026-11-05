import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from auralis_intelligence.recall import Preference, RecallRequest, WeightConfig, load_a_v2


APP = Path(__file__).resolve().parents[1]
FIXTURES = APP / "tests" / "fixtures"
SNAPSHOT = FIXTURES / "synthetic-snapshot.json"


@pytest.fixture(scope="module")
def engine():
    return load_a_v2(SNAPSHOT, weights=WeightConfig(2.0, 2.0))


def test_a_v2_excludes_seeds_and_filters_before_limiting(engine):
    request = RecallRequest(limit=2, seeds=(Preference("a0"),))
    result = engine.recall(request)
    assert [item.album_id for item in result.candidates] == ["a1", "a2"]
    assert result.algorithm == "A-V2"

    filtered = engine.recall(RecallRequest(limit=2, seeds=request.seeds, forbidden_tags=("electronic",)))
    assert [item.album_id for item in filtered.candidates] == ["a1"]
    assert filtered.reason == "insufficient_candidates"
    assert filtered.returned_count == 1


def test_explicit_genre_without_album_seed(engine):
    result = engine.recall(RecallRequest(limit=2, tags=(Preference("jazz"),), mode="familiar"))
    assert result.returned_count == 2
    assert result.candidates[0].album_id == "a3"


def test_negative_only_returns_controlled_empty_result(engine):
    result = engine.recall(RecallRequest(limit=2, disliked_tags=("jazz",)))
    assert result.candidates == ()
    assert result.reason == "unsupported_positive_input"


def invoke(request):
    env = {**os.environ, "PYTHONPATH": str(APP / "src")}
    return subprocess.run(
        [sys.executable, "-m", "auralis_intelligence.recall", "--snapshot", str(SNAPSHOT),
         "--request", str(request), "--special-weight", "2", "--focus-weight", "2"],
        env=env, capture_output=True, text=True, timeout=30,
    )


def test_subprocess_serializes_result():
    run = invoke(FIXTURES / "request.json")
    assert run.returncode == 0, run.stderr
    assert json.loads(run.stdout) == {
        "format_version": 1, "algorithm": "A-V2", "dataset_id": "synthetic-code-check-v1",
        "window": "synthetic", "requested_count": 2, "returned_count": 2,
        "reason": "complete", "candidate_ids": ["a1", "a2"],
    }


@pytest.mark.parametrize("request_body", [
    {"seeds": [{"id": "a0"}]},
    {"limit": 2, "seeds": [{"id": "missing"}]},
    {"limit": 2, "text": "raw text"},
])
def test_bad_request_does_not_emit_candidates(request_body, tmp_path):
    request = tmp_path / "request.json"
    request.write_text(json.dumps(request_body), encoding="utf-8")
    run = invoke(request)
    assert run.returncode == 2
    assert not run.stdout
