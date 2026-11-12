"""Neutral text does not invent a preference or erase substantive input."""

import asyncio

import pytest
from auralis_intelligence.preferences.fast import GENERIC, FastPreferencesStage
from test_preferences import FIXTURE, execution_for, numbered_fixture_replies, raw


@pytest.mark.parametrize("text", ["随便", "随便听听！", "都行", "推荐几张专辑", "anything is fine"])
def test_neutral_text_without_likes_returns_java_curated_signal_without_model(text):
    async def run():
        execution, _, adapter, original = execution_for(raw(text), [])
        accepted = await FastPreferencesStage(original.gateway, original.reference)(execution)
        assert accepted["has_positive_signal"] is False
        assert accepted["tags"] == []
        assert accepted["parser_revision"] == "generic-discovery.v2"
        assert not adapter.requests
        assert any(item["description"] == text for item in accepted["soft_preferences"])

    asyncio.run(run())


@pytest.mark.parametrize(
    "text",
    ["别太吵，适合晚上听", "随便，但不要金属", "想听爵士", "recommend music for a quiet evening"],
)
def test_listening_preferences_and_constraints_cannot_take_neutral_shortcut(text):
    assert not GENERIC.fullmatch(text)


def test_substantive_text_still_runs_both_model_passes():
    async def run():
        case = next(case for case in FIXTURE["cases"] if case["id"] == "explicit-genre")
        execution, _, adapter, original = execution_for(
            raw(case["request_text"]), numbered_fixture_replies(case)
        )
        accepted = await FastPreferencesStage(original.gateway, original.reference)(execution)
        assert len(adapter.requests) == 2
        assert accepted["has_positive_signal"] is True

    asyncio.run(run())


def test_neutral_text_preserves_selected_positive_genre():
    async def run():
        execution, _, adapter, original = execution_for(
            raw("随便", liked=[{"genre_id": "genre:jazz", "strength": "special", "focused": True}]),
            [],
        )
        accepted = await FastPreferencesStage(original.gateway, original.reference)(execution)
        assert accepted["has_positive_signal"] is True
        assert accepted["tags"][0]["id"] == "genre:jazz"
        assert accepted["tags"][0]["strength"] == "special"
        assert not adapter.requests

    asyncio.run(run())


def test_model_recognized_open_request_has_no_invented_positive_direction():
    async def run():
        text = "你替我拿个主意吧，听哪张都可以"
        assert not GENERIC.fullmatch(text)
        execution, _, adapter, original = execution_for(raw(text), [{"branches": []}])
        accepted = await FastPreferencesStage(original.gateway, original.reference)(execution)
        assert len(adapter.requests) == 1
        assert accepted["has_positive_signal"] is False
        assert accepted["tags"] == []
        assert accepted["soft_preferences"][0]["description"] == text

    asyncio.run(run())
