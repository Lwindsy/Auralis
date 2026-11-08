"""Shared boundary cases and independently asserted reaffirmation behavior."""

import json
from pathlib import Path

from preference_wire import run


def test_shared_preference_cases_and_user_confirmed_flags():
    path = Path(__file__).parent / "fixtures/preference-authority-cases.json"
    cases = json.loads(path.read_text(encoding="utf-8"))["cases"]
    results = run(path)
    assert len(results) == len(cases)
    for case, output in zip(cases, results, strict=True):
        assert output.get("error") == case["expected_error"], case["name"]
        if "expected_tag" in case:
            assert output["result"]["tags"] == [case["expected_tag"]]
        if case["name"] in {"no-positive", "external-False"}:
            assert not output["result"]["has_positive_signal"]
        if case["name"] == "external-True":
            assert output["result"]["seeds"] == []
            assert output["result"]["tags"][0]["focused"] is True
