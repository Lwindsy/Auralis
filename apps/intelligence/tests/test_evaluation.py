"""No-network evidence for the AI-023 manifest and cost gate."""

import hashlib
import json

from auralis_intelligence.agents.demo import DemoHarness
from auralis_intelligence.contracts import validate_definition
from auralis_intelligence.evaluation.genre_preferences import _case_start, build_plan
from auralis_intelligence.providers import ModelBinding


def test_preflight_hashes_inputs_and_prices_without_enabling_live_calls(tmp_path):
    dataset = {"split": "development", "status": "frozen", "questions": [{
        "id": "case-1", "family": "family-1", "language": "en", "coverage": ["named_genre"],
        "evaluation_scope": "genre", "request_text": "Play jazz",
    }]}
    reference = {
        "branches": [{"id": "branch", "name": "Jazz", "aliases": [], "description": "branch", "genre_ids": ["jazz"]}],
        "genres": [{"id": "jazz", "name": "jazz", "aliases": [], "description": "improvised music"}],
    }
    rows = {}
    for role in ("interpret", "screen", "select", "write"):
        rows[role] = {"ref": role, "revision": "test", "model": "test-model", "price_revision": "test-price",
                      "max_input_tokens": 24000, "max_output_tokens": 100, "structured_outputs": True,
                      "web_search": role == "screen", "live_enabled": False, "price_source": "https://example.test/price",
                      "input_microusd_per_million": 100000, "cached_input_microusd_per_million": 10000,
                      "output_microusd_per_million": 500000, "search_microusd_per_call": 10000}
    paths = {name: tmp_path / f"{name}.json" for name in ("dataset", "reference", "bindings")}
    values = {"dataset": dataset, "reference": reference, "bindings": rows}
    for name, path in paths.items():
        path.write_text(json.dumps(values[name]), encoding="utf-8")
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = {"run_id": "test-run", "dataset": str(paths["dataset"]), "dataset_sha256": sha(paths["dataset"]),
                "reference": str(paths["reference"]), "reference_sha256": sha(paths["reference"]),
                "model_bindings": str(paths["bindings"]), "case_ids": ["case-1"], "second_pass_genre_limit": 40,
                "trial_budget_microusd": 60000, "per_case_budget_microusd": 20000,
                "provider_key_env": "AURALIS_TEST_KEY", "output_dir": str(tmp_path / "output")}
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    plan = build_plan(manifest_path)

    assert plan["paid_requests"] is False and plan["case_count"] == 1
    assert plan["estimated_maximum_microusd"] > 0
    assert plan["inputs_within_binding"] and plan["cases_within_budget"] and plan["within_configured_budget"]
    assert plan["live_ready"] is False

    manifest["expected_pass1_branches"] = {"case-1": ["branch"]}
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    expected_plan = build_plan(manifest_path)
    assert expected_plan["expected_pass1_branches"] == {"case-1": ["branch"]}

    harness = DemoHarness(scenario="text", r=3, k=2, n=1, root=tmp_path)
    try:
        bindings = {role: ModelBinding(**row) for role, row in rows.items()}
        start = _case_start(harness.request, manifest, bindings, dataset["questions"][0], 0)
        validate_definition("RoundStart", start)
        assert start["budget"]["limits"]["lane_caps"] == {"FOREGROUND": 20000, "BACKGROUND": 0}
    finally:
        harness.close()
