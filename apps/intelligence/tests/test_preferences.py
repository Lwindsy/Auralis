"""Synthetic two-pass, Java-double merge and raw-text lifetime evidence."""

import asyncio
from copy import deepcopy
from functools import partial
import json
from pathlib import Path

import pytest

from auralis_intelligence.agents import CallResult, Execution, Usage
from auralis_intelligence.agents.control_double import JavaControlDouble
from auralis_intelligence.contracts import content_digest, validate_definition
from auralis_intelligence.preferences import PreferenceError, PreferencesStage, ReferenceCatalog, merge_preferences
from auralis_intelligence.preferences.reference import locate
from auralis_intelligence.providers import ModelBinding, ModelGateway
from auralis_intelligence.providers.testing import ScriptedModelAdapter
from test_agent_runtime import start

FIXTURE = json.loads((Path(__file__).parent / "fixtures/preference-cases.json").read_text(encoding="utf-8"))
REFERENCE = ReferenceCatalog(FIXTURE["reference"])


def raw(text="", *, liked=None, disliked=None, albums=None):
    value = {"liked_genres": liked or [], "disliked_genre_ids": disliked or [], "liked_albums": albums or [],
             "mode": "explore", "language": "zh", "request_text": text}
    value["input_digest"] = content_digest(value, "input_digest")
    return value


def proposal(value, *, preferences=None, constraints=None, unresolved=None):
    result = {"input_digest": value["input_digest"], "reference_revision": REFERENCE.revision,
              "parser_revision": "synthetic-v1", "preferences": preferences or [], "constraints": constraints or [],
              "unresolved_constraints": unresolved or [], "soft_preferences": []}
    result["proposal_digest"] = content_digest(result, "proposal_digest")
    return result


def execution_for(value, outputs, *, reference=REFERENCE, seed_descriptions=None):
    request = start()
    request["input"]["raw"] = value
    request["model_bindings"]["interpret"].update(max_input_tokens=100000, max_output_tokens=3000)
    request["payload_digest"] = content_digest(request)
    control = JavaControlDouble("java-test", price_per_token=0)
    control.preference_resolver = partial(merge_preferences, reference=reference, seed_descriptions=seed_descriptions)
    control.register(request)
    execution = Execution(request, control)
    adapter = ScriptedModelAdapter([x if isinstance(x, CallResult) else CallResult(x, usage=Usage(2, 2, 0)) for x in outputs])
    binding = ModelBinding(**request["model_bindings"]["interpret"])
    stage = PreferencesStage(ModelGateway(adapter, {binding.ref: binding}), reference)
    return execution, control, adapter, stage


@pytest.mark.parametrize("case", FIXTURE["cases"], ids=lambda row: row["id"])
def test_two_pass_frozen_synthetic_cases(case):
    async def run():
        value = raw(case["request_text"])
        execution, control, adapter, stage = execution_for(value, [case["expected_pass_1"], case["expected_pass_2"]])
        if case["expected_pass_2"]["unresolved_constraints"]:
            with pytest.raises(PreferenceError, match="UNSUPPORTED_CONSTRAINT") as caught:
                await stage(execution)
            assert caught.value.status == "NEEDS_INPUT"
            assert not any(operation == "preference-resolution" for _, operation in control.events)
        else:
            snapshot = await stage(execution)
            validate_definition("EffectivePreferenceSnapshot", snapshot)
            assert "request_text" not in json.dumps(snapshot) and "quote" not in json.dumps(snapshot)
            for item in case["expected_pass_2"]["soft_preferences"]:
                assert any(row["description"] == item["description"] for row in snapshot["soft_preferences"])
            assert execution.start["input"]["raw"]["request_text"] == value["request_text"]
        assert len(adapter.requests) == 2
        assert all(request.input["request_text"] == value["request_text"] for _, request in adapter.requests)
        genres = adapter.requests[1][1].input["genres"]
        assert len(genres) == len({row["id"] for row in genres})
        assert len(control.invocations) == 2
        await execution.cleanup()
        assert execution.start["input"]["raw"] is None
    asyncio.run(run())


def test_blank_text_preserves_preselected_flags_and_seed_descriptions_without_model():
    async def run():
        value = raw(" \n ", liked=[{"genre_id": "genre:jazz", "strength": "special", "focused": True}],
                    albums=[{"release_group_id": "album-1", "external": None, "strength": "normal", "focused": True}])
        execution, _, adapter, stage = execution_for(value, [], seed_descriptions={"album-1": "Artist / Album"})
        snapshot = await stage(execution)
        assert snapshot["tags"] == [{"id": "genre:jazz", "strength": "special", "focused": True}]
        assert snapshot["seeds"] == [{"id": "album-1", "strength": "normal", "focused": True}]
        assert any("Artist / Album" in row["description"] for row in snapshot["soft_preferences"])
        assert not adapter.requests
        execution.start["input"]["raw"] = None
        assert await stage(execution) == snapshot
        assert not adapter.requests
    asyncio.run(run())


@pytest.mark.parametrize("kind,basis,tag,disliked,forbidden,required", [
    ("prefer", "explicit", True, False, False, False),
    ("prefer", "inferred", False, True, False, False),
    ("avoid", "explicit", False, True, False, False),
    ("require", None, True, False, False, True),
    ("exclude", None, False, True, True, False),
])
def test_explicit_and_inferred_merge(kind, basis, tag, disliked, forbidden, required):
    value = raw("爵士", disliked=["genre:jazz"])
    row = {"id": "genre:jazz", "kind": kind, "text_span": {"start": 0, "end": 2}}
    key = "preferences" if basis else "constraints"
    if basis:
        row["basis"] = basis
    snapshot = merge_preferences(value, proposal(value, **{key: [row]}), reference=REFERENCE)
    assert bool(snapshot["tags"]) == tag
    assert bool(snapshot["disliked_tags"]) == disliked
    assert bool(snapshot["forbidden_tags"]) == forbidden
    assert bool(snapshot["required_tags"]) == required


def test_text_changes_only_mentioned_genre_not_seed_or_other_flags():
    value = raw("爵士", liked=[{"genre_id": g, "strength": "special", "focused": True} for g in ("genre:jazz", "genre:ambient")],
                albums=[{"release_group_id": "album", "external": None, "strength": "special", "focused": True}])
    row = {"id": "genre:jazz", "kind": "avoid", "basis": "explicit", "text_span": {"start": 0, "end": 2}}
    snapshot = merge_preferences(value, proposal(value, preferences=[row]), reference=REFERENCE, seed_descriptions={"album": "Artist / Title"})
    assert snapshot["tags"] == [{"id": "genre:ambient", "strength": "special", "focused": True}]
    assert snapshot["seeds"] == [{"id": "album", "strength": "special", "focused": True}]
    assert not snapshot["forbidden_tags"] and not snapshot["disliked_tags"]


def test_external_album_only_maps_supplied_genres_and_retains_description():
    value = raw(albums=[{"release_group_id": None, "external": {"mb_release_group_id": "external", "mapped_genre_ids": ["genre:jazz"]}, "strength": "special", "focused": True}])
    snapshot = merge_preferences(value, proposal(value), reference=REFERENCE, seed_descriptions={"external": "External / Album"})
    assert snapshot["seeds"] == []
    assert snapshot["tags"] == [{"id": "genre:jazz", "strength": "special", "focused": True}]
    with pytest.raises(PreferenceError, match="INPUT_DESCRIPTION_UNAVAILABLE") as caught:
        merge_preferences(value, proposal(value), reference=REFERENCE)
    assert caught.value.status == "FAILED"


@pytest.mark.parametrize("first,second,code", [
    ({"branches": [{"id": "made-up", "quote": "爵士"}]}, None, "INVALID_ID"),
    ({"branches": [{"id": "branch:jazz", "quote": "不存在"}]}, None, "INVALID_SPAN"),
    ({"branches": []}, None, "INVALID_OUTPUT"),
    (CallResult(finish="TRUNCATED"), None, "INTERPRETATION_FAILED"),
    (CallResult(outcome="UNCERTAIN"), None, "INTERPRETATION_FAILED"),
    ({"branches": [{"id": "branch:jazz", "quote": "爵士"}]}, {"preferences": [{"id": "genre:metal", "kind": "prefer", "basis": "explicit", "quote": "爵士"}], "constraints": [], "unresolved_constraints": [], "soft_preferences": []}, "INVALID_ID"),
])
def test_model_failures_never_fall_back_to_old_preselection(first, second, code):
    async def run():
        execution, control, _, stage = execution_for(raw("爵士"), [first] + ([] if second is None else [second]))
        with pytest.raises(PreferenceError, match=code):
            await stage(execution)
        assert execution.start["input"]["accepted_preferences"] is None
        assert not any(op == "preference-resolution" for _, op in control.events)
    asyncio.run(run())


def test_unique_codepoint_spans_and_reference_binding():
    assert locate("🎧来点爵士", "爵士") == {"start": 3, "end": 5}
    with pytest.raises(PreferenceError, match="INVALID_SPAN"):
        locate("安静，还是安静", "安静")
    with pytest.raises(PreferenceError, match="VERSION_MISMATCH"):
        ReferenceCatalog(FIXTURE["reference"], expected_revision="other")
    reference = ReferenceCatalog({"branches": [], "genres": []})
    with pytest.raises(PreferenceError, match="REFERENCE_UNAVAILABLE"):
        reference.branch_material()
    duplicated = deepcopy(FIXTURE["reference"])
    duplicated["genres"].append(deepcopy(duplicated["genres"][0]))
    with pytest.raises(PreferenceError, match="REFERENCE_UNAVAILABLE"):
        ReferenceCatalog(duplicated)


def test_known_missing_descriptions_do_not_reject_available_reference_material():
    document = deepcopy(FIXTURE["reference"])
    identifier = document["branches"][0]["genre_ids"][0]
    next(row for row in document["genres"] if row["id"] == identifier)["description"] = None
    reference = ReferenceCatalog(document)
    expanded = reference.expand([document["branches"][0]["id"]])
    assert expanded and identifier not in {row["id"] for row in expanded}
    assert reference.genres[identifier]["name"]


def test_conflicting_hard_constraints_and_parallel_interests():
    value = raw("爵士")
    constraints = [{"id": "genre:jazz", "kind": kind, "text_span": {"start": 0, "end": 2}} for kind in ("require", "exclude")]
    with pytest.raises(PreferenceError, match="PREFERENCE_CONFLICT") as caught:
        merge_preferences(value, proposal(value, constraints=constraints), reference=REFERENCE)
    assert caught.value.status == "NEEDS_INPUT"
    preferences = [{"id": identifier, "kind": "prefer", "basis": "explicit", "text_span": {"start": 0, "end": 2}} for identifier in ("genre:jazz", "genre:ambient")]
    snapshot = merge_preferences(value, proposal(value, preferences=preferences), reference=REFERENCE)
    assert len(snapshot["tags"]) == 2 and not snapshot["required_tags"]


def test_inference_preserves_explicit_positive_flags():
    value = raw("安静", liked=[{"genre_id": "genre:jazz", "strength": "special", "focused": True}])
    row = {"id": "genre:jazz", "kind": "avoid", "basis": "inferred", "text_span": {"start": 0, "end": 2}}
    snapshot = merge_preferences(value, proposal(value, preferences=[row]), reference=REFERENCE)
    assert snapshot["tags"] == [{"id": "genre:jazz", "strength": "special", "focused": True}]
    assert not any(item["kind"] == "avoid" for item in snapshot["soft_preferences"])


@pytest.mark.parametrize("inferred,explicit", [("prefer", "exclude"), ("avoid", "require")])
def test_explicit_constraint_wins_over_opposite_inference(inferred, explicit):
    async def run():
        first = {"branches": [{"id": "branch:jazz", "quote": "爵士"}]}
        second = {"preferences": [{"id": "genre:jazz", "kind": inferred, "basis": "inferred", "quote": "爵士"}],
                  "constraints": [{"id": "genre:jazz", "kind": explicit, "quote": "爵士"}],
                  "unresolved_constraints": [], "soft_preferences": []}
        execution, _, _, stage = execution_for(raw("爵士"), [first, second])
        snapshot = await stage(execution)
        assert bool(snapshot["tags"]) == (explicit == "require")
        assert snapshot["forbidden_tags"] == (["genre:jazz"] if explicit == "exclude" else [])
        assert snapshot["required_tags"] == (["genre:jazz"] if explicit == "require" else [])
        assert all(item["basis"] == "explicit" for item in snapshot["soft_preferences"])
    asyncio.run(run())


def test_empty_expansion_and_second_pass_invalid_quote_stop_before_control():
    async def run():
        reference_doc = deepcopy(FIXTURE["reference"])
        reference_doc["branches"][0]["genre_ids"] = []
        reference = ReferenceCatalog(reference_doc)
        first = {"branches": [{"id": "branch:jazz", "quote": "爵士"}]}
        execution, control, adapter, stage = execution_for(raw("爵士"), [first], reference=reference)
        with pytest.raises(PreferenceError, match="REFERENCE_UNAVAILABLE"):
            await stage(execution)
        assert len(adapter.requests) == 1
        second = {"preferences": [], "constraints": [], "unresolved_constraints": [], "soft_preferences": [
            {"description": "希望安静", "kind": "prefer", "basis": "explicit", "quote": "并不存在"}]}
        execution, control, _, stage = execution_for(raw("爵士"), [first, second])
        with pytest.raises(PreferenceError, match="INVALID_SPAN"):
            await stage(execution)
        assert not any(op == "preference-resolution" for _, op in control.events)
    asyncio.run(run())
