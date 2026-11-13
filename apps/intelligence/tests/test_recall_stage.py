"""Real A-V2 synthetic cases; optional CLI compares an explicit local fixed request."""

import asyncio
import json
import threading
from dataclasses import asdict
from pathlib import Path
from types import SimpleNamespace

import pytest
from auralisy_intelligence.agents import Execution
from auralisy_intelligence.agents.control_double import JavaControlDouble
from auralisy_intelligence.agents.recall_stage import RecallStage, recall_request
from auralisy_intelligence.contracts import (
    ContractError,
    content_digest,
    digest,
    validate_definition,
)
from auralisy_intelligence.recall import Preference, RecallRequest, WeightConfig, load_a_v2
from test_agent_runtime import start

SNAPSHOT = Path(__file__).parent / "fixtures/synthetic-snapshot.json"
WEIGHTS = WeightConfig(1, 1)


def preferences(**changes):
    value = {
        "resolution_id": "synthetic",
        "input_digest": digest("synthetic"),
        "reference_revision": "synthetic",
        "parser_revision": "synthetic",
        "seeds": [{"id": "a0", "strength": "normal", "focused": False}],
        "tags": [],
        "disliked_tags": [],
        "forbidden_tags": [],
        "required_tags": [],
        "soft_preferences": [],
        "overrides": [],
        "mode": "explore",
        **changes,
    }
    value["has_positive_signal"] = bool(value["seeds"] or value["tags"])
    value["effective_preference_digest"] = content_digest(value, "effective_preference_digest")
    return value


def setup(
    value,
    *,
    limit=2,
    excluded=(),
    snapshot=SNAPSHOT,
    dataset="synthetic-code-check-v1",
    window="synthetic",
    engine=None,
    **audience_bounds,
):
    request = start()
    request["input"].update(
        raw=None,
        accepted_preferences=value,
        exclusions=[{"release_group_id": i, "reason": "HEARD"} for i in excluded],
    )
    request["input"]["exclusion_digest"] = digest(request["input"]["exclusions"])
    request["selection_config"].update(
        recall_target_count=limit, shortlist_target_count=1, recommendation_target_count=1
    )
    request["recall_binding"] = {
        "strategy_revision": "A-V2",
        "data_snapshot_id": dataset,
        "window": window,
        "weights_digest": digest(asdict(WEIGHTS)),
    }
    request["payload_digest"] = content_digest(request)
    execution = Execution(request, JavaControlDouble("java-test"))
    return execution, RecallStage(
        snapshot,
        binding=request["recall_binding"],
        weights=WEIGHTS,
        engine=engine,
        **audience_bounds,
    )


def test_all_request_fields_are_mapped_without_reinterpretation():
    snapshot = preferences(
        tags=[{"id": "jazz", "strength": "special", "focused": True}],
        disliked_tags=["rock"],
        forbidden_tags=["electronic"],
        required_tags=["jazz"],
        mode="surprise",
    )
    request = recall_request(snapshot, [{"release_group_id": "a3", "reason": "HEARD"}], 7)
    assert request == RecallRequest(
        limit=7,
        seeds=(Preference("a0"),),
        tags=(Preference("jazz", "special", True),),
        disliked_tags=("rock",),
        forbidden_tags=("electronic",),
        required_tags=("jazz",),
        excluded_ids=("a3",),
        mode="surprise",
    )


@pytest.mark.parametrize(
    "changes,excluded,status,ids,shortfall",
    [
        ({}, (), "COMPLETE", ["a1", "a2"], 0),
        ({"forbidden_tags": ["electronic"]}, (), "COMPLETE", ["a1"], 1),
        ({}, ("a1", "a2"), "NO_COVERAGE", [], 2),
        ({"seeds": [], "disliked_tags": ["jazz"]}, (), "UNSUPPORTED_INPUT", [], 2),
        (
            {"seeds": [{"id": "unknown", "strength": "normal", "focused": False}]},
            (),
            "UNSUPPORTED_INPUT",
            [],
            2,
        ),
    ],
)
def test_result_status_and_order(changes, excluded, status, ids, shortfall):
    async def run():
        value = preferences(**changes)
        execution, stage = setup(value, excluded=excluded)
        proposal = await stage(execution, value)
        validate_definition("RecallProposal", proposal)
        assert proposal["execution_status"] == status
        assert [row["release_group_id"] for row in proposal["candidates"]] == ids
        assert proposal["shortfall"] == shortfall and not proposal["truncated"]
        if not value["has_positive_signal"]:
            assert stage.engine is None and proposal["raw_reason"] == "unsupported_positive_input"

    asyncio.run(run())


def test_artifact_failure_does_not_emit_candidates(tmp_path):
    async def run():
        value = preferences()
        execution, stage = setup(value, snapshot=tmp_path / "absent")
        result = await stage(execution, value)
        assert result["execution_status"] == "UNAVAILABLE" and not result["candidates"]
        broken = tmp_path / "broken.json"
        broken.write_text('{"format_version": 1}', encoding="utf-8")
        execution, stage = setup(value, snapshot=broken)
        assert (await stage(execution, value))["execution_status"] == "UNAVAILABLE"

    asyncio.run(run())


def test_audience_range_filters_before_top_result_is_selected():
    engine = load_a_v2(SNAPSHOT, weights=WEIGHTS)
    engine.bind_full_audience(SimpleNamespace(counts={"a1": 101, "a2": 100}))
    request = RecallRequest(limit=1, seeds=(Preference("a0"),))

    result = engine.recall(request, min_listeners=0, max_listeners=100)
    assert [candidate.album_id for candidate in result.candidates] == ["a2"]

    result = engine.recall(request, min_listeners=101)
    assert [candidate.album_id for candidate in result.candidates] == ["a1"]


def test_binding_checks_and_continuation_recall_prohibition():
    async def run():
        value = preferences()
        execution, stage = setup(value)
        execution.start["recall_binding"]["data_snapshot_id"] = "other"
        with pytest.raises(ContractError, match="BINDING_MISMATCH"):
            await stage(execution, value)
        execution, stage = setup(value, dataset="wrong-artifact")
        with pytest.raises(ContractError, match="BINDING_MISMATCH"):
            await stage(execution, value)
        execution, stage = setup(value, window="wrong-window")
        with pytest.raises(ContractError, match="BINDING_MISMATCH"):
            await stage(execution, value)
        execution, stage = setup(value)
        execution.start["input"]["accepted_preferences"] = preferences(mode="surprise")
        with pytest.raises(ContractError, match="BINDING_MISMATCH"):
            await stage(execution, value)
        execution, stage = setup(value)
        execution.start["start_mode"] = "CONTINUE"
        with pytest.raises(ContractError, match="RECALL_NOT_ALLOWED"):
            await stage(execution, value)

    asyncio.run(run())


def test_candidate_proposal_is_accepted_by_java_double():
    async def run():
        value = preferences()
        execution, stage = setup(value)
        control = JavaControlDouble(
            "java-test",
            catalog={
                identifier: {
                    "title": identifier,
                    "artist_credit": "Synthetic Artist",
                    "first_release_year": 2000,
                    "release_type": "Album",
                    "primary_genres": [],
                }
                for identifier in ("a1", "a2")
            },
        )
        control.register(execution.start)
        execution.control = control
        proposal = await stage(execution, value)
        accepted = await execution.control_request("candidate-validation", proposal)
        validate_definition("CandidateBundle", accepted)
        assert accepted["counts"] == {"proposed": 2, "accepted": 2, "rejected": 0}
        assert accepted["binding"]["recall_binding"] == stage.binding

    asyncio.run(run())


@pytest.mark.parametrize("cancel", [True, False, "after_timeout"])
def test_cancel_and_timeout_wait_until_cpu_work_physically_stops(cancel):
    async def run():
        entered, release, stopped = threading.Event(), threading.Event(), threading.Event()
        actual = load_a_v2(SNAPSHOT, weights=WEIGHTS)

        class WaitingEngine:
            def recall(self, request):
                entered.set()
                assert release.wait(5)
                stopped.set()
                return actual.recall(request)

        value = preferences()
        execution, stage = setup(value, engine=WaitingEngine())
        execution.start["agent_policy"]["timing"]["recall_timeout_ms"] = (
            5000 if cancel is True else 1
        )
        task = asyncio.create_task(stage(execution, value))
        await asyncio.to_thread(entered.wait, 5)
        if cancel is True:
            task.cancel()
        await asyncio.sleep(0.02)
        assert not task.done() and not stopped.is_set()
        if cancel == "after_timeout":
            task.cancel()
            await asyncio.sleep(0.02)
            assert not task.done() and not stopped.is_set()
            task.cancel()  # Repeated cancellation still cannot release the CPU worker.
            await asyncio.sleep(0.02)
            assert not task.done() and not stopped.is_set()
        release.set()
        if cancel:
            with pytest.raises(asyncio.CancelledError):
                await task
        else:
            assert (await task)["execution_status"] == "TIMED_OUT"
        assert stopped.is_set()

    asyncio.run(run())


def compare_local(snapshot_path: Path, request_path: Path) -> dict:
    """Read only the first already-fixed request; never export listener records."""
    header = json.loads((snapshot_path / "dataset.json").read_text(encoding="utf-8"))
    with request_path.open(encoding="utf-8") as stream:
        row = json.loads(next(stream))
    raw_request = row.get("request", row)
    raw_request = {**raw_request, "limit": 20}

    def weighted(key):
        return tuple(Preference(**item) for item in raw_request.get(key, []))

    baseline_request = RecallRequest(
        limit=20,
        seeds=weighted("seeds"),
        tags=weighted("tags"),
        **{
            key: tuple(raw_request.get(key, []))
            for key in ("disliked_tags", "forbidden_tags", "required_tags", "excluded_ids")
        },
        mode=raw_request.get("mode", "explore"),
    )
    value = preferences(
        **{
            key: [
                {k: v for k, v in asdict(item).items() if k != "weight"}
                for item in getattr(baseline_request, key)
            ]
            for key in ("seeds", "tags")
        },
        **{
            key: list(getattr(baseline_request, key))
            for key in ("disliked_tags", "forbidden_tags", "required_tags")
        },
        mode=baseline_request.mode,
    )
    engine = load_a_v2(snapshot_path, weights=WEIGHTS)
    direct = engine.recall(baseline_request)
    execution, stage = setup(
        value,
        limit=20,
        excluded=baseline_request.excluded_ids,
        snapshot=snapshot_path,
        dataset=header["dataset_id"],
        window=header["window"],
        engine=engine,
    )
    result = asyncio.run(stage(execution, value))
    expected = [candidate.album_id for candidate in direct.candidates]
    actual = [candidate["release_group_id"] for candidate in result["candidates"]]
    assert expected == actual and result["raw_reason"] == direct.reason
    assert result["recall_binding"]["data_snapshot_id"] == direct.dataset_id
    assert result["recall_binding"]["window"] == direct.window
    return {
        "dataset_id": header["dataset_id"],
        "dataset_fingerprint": header["fingerprint"],
        "window": header["window"],
        "request_digest": digest(raw_request),
        "ordered_top20_digest": digest(actual),
        "returned": len(actual),
        "match": True,
        "execution_status": result["execution_status"],
        "raw_reason": result["raw_reason"],
        "weights_digest": stage.binding["weights_digest"],
    }


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    arguments = parser.parse_args()
    print(
        json.dumps(
            compare_local(arguments.snapshot, arguments.request), ensure_ascii=False, indent=2
        )
    )


def test_tag_only_pool_covers_directions_without_changing_scorer_or_hard_constraints():
    from auralisy_intelligence.recall import Candidate, RecallResult

    class Engine:
        def __init__(self):
            self.calls = []

        def recall(self, request, **kwargs):
            self.calls.append((request, kwargs))
            ids = (
                [f"hyper-{i}" for i in range(5)]
                if len(request.tags) > 1
                else [request.tags[0].id + "-0"]
            )
            candidates = tuple(Candidate(i, 1.0) for i in ids[: request.limit])
            return RecallResult(
                candidates,
                request.limit,
                len(candidates),
                "complete",
                "A-V2",
                "synthetic-code-check-v1",
                "synthetic",
            )

    engine = Engine()
    _, stage = setup(preferences(), engine=engine, min_listeners=0, max_listeners=100)
    query = RecallRequest(
        limit=5,
        tags=tuple(Preference(i) for i in ["hyper", "post", "trip"]),
        forbidden_tags=("blocked",),
        excluded_ids=("heard",),
        disliked_tags=("avoid",),
    )
    status, _, result = stage._compute(query)
    assert status == "COMPLETE"
    assert [c.album_id for c in result.candidates] == [
        "hyper-0",
        "post-0",
        "trip-0",
        "hyper-1",
        "hyper-2",
    ]
    assert len(engine.calls) == 4
    for request, options in engine.calls:
        assert request.forbidden_tags == ("blocked",)
        assert request.disliked_tags == ("avoid",)
        assert request.excluded_ids == ("heard",)
        assert options == {"min_listeners": 0, "max_listeners": 100}
    assert all(request.tags[0].id in request.required_tags for request, _ in engine.calls[1:])
