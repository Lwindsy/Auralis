"""Audience gates, prepared evidence and one-call recommendation behavior."""

import asyncio
import json
from types import SimpleNamespace

import pytest
from auralis_intelligence.agents.demo import DemoHarness, _reply
from auralis_intelligence.agents.pipeline import FirstBatchPipeline
from auralis_intelligence.album_reviews.batch import (
    BatchResearchStage,
    BatchSelectStage,
    BatchWriteStage,
)
from auralis_intelligence.contracts import content_digest
from auralis_intelligence.preferences.fast import GENERIC


def test_generic_route_never_swallows_specific_constraints():
    for text in [
        "给我推一些新专辑，符合我品味的",
        "推荐几张专辑",
        "please recommend some new albums",
        "帮我推荐一些音乐",
    ]:
        assert GENERIC.fullmatch(text)
    for text in [
        "推荐专辑，不要金属",
        "只要今年发行的专辑",
        "recommend albums without vocals",
        "给我推一些新专辑，越吵越好",
    ]:
        assert not GENERIC.fullmatch(text)


@pytest.mark.parametrize("minimum", [10, 50, 500])
@pytest.mark.parametrize(
    "background,insufficient,cold",
    [(False, 0, False), (True, 0, False), (False, 2, False), (False, 4, False), (True, 0, True)],
)
def test_batch_one_call_and_audience_verified_at_publication(
    tmp_path, background, insufficient, cold, minimum
):
    async def run():
        harness = DemoHarness(root=tmp_path, scenario="genre", r=5, k=5, n=3)
        try:
            policy = harness.request["agent_policy"]
            policy["audience_min_listeners"] = minimum
            policy["audience_binding"] = {
                "dataset_id": "full-synthetic",
                "window": "0",
                "sample_listeners": 1000,
                "snapshot_digest": "sha256:" + "a" * 64,
            }
            policy["digest"] = content_digest(policy, "digest")
            harness.request["payload_digest"] = content_digest(harness.request)
            for identifier in harness.control.catalog:
                harness.control.candidate_audiences[identifier] = {
                    "source": "LISTENBRAINZ_SAMPLE",
                    "dataset_id": "full-synthetic",
                    "window": "0",
                    "sample_listeners": 1000,
                    "listeners": minimum - 1 if identifier == "album-1" else minimum,
                }
            evidence = SimpleNamespace(
                sources=lambda identifier: [
                    {"url": f"https://{host}.example/{identifier}"} for host in ["one", "two"]
                ]
            )
            if cold:

                class ColdWindow:
                    cold = True
                    failed_ids = set()

                    def __init__(self):
                        self.prepared = set()

                    async def prepare(self, execution, candidates, tools):
                        self.prepared = {c["release_group_id"] for c in candidates}

                    def sources(self, identifier):
                        return (
                            [
                                {"url": f"https://{host}.example/{identifier}"}
                                for host in ["one", "two"]
                            ]
                            if identifier in self.prepared
                            else []
                        )

                    def take_reads(self, identifier):
                        return None

                    async def discard_unselected(self, selected):
                        pass

                evidence = ColdWindow()
            batch = BatchResearchStage(
                harness.model,
                harness.tools,
                evidence,
                genre_names={"jazz": "爵士"},
                candidate_limit=3 if cold else 8,
            )
            writer = BatchWriteStage(harness.model, harness.tools, batch=batch)
            selector = BatchSelectStage(harness.model, batch=batch)
            base = harness.pipeline(harness.request)
            from auralis_intelligence.agents.background import RoundPipeline

            implementation = RoundPipeline if background else FirstBatchPipeline
            pipeline = implementation(
                base.preferences, base.recall, batch, writer, selector, generate_long_reviews=False
            )
            seen = []

            def reply(binding, request):
                seen.append(request.schema_name)
                if request.schema_name != "batch_v6":
                    return _reply(binding, request)
                from auralis_intelligence.agents import CallResult, Usage

                text = "这张专辑在合成测试资料中呈现克制的编配，钢琴与轻缓鼓点之间留有清晰空间，旋律以渐进的方式展开而不过度依赖厚重音墙[R1]。另一份资料强调人声与吉他的亲近感；如果这次想在深夜安静地听，可以留意这种舒缓而细致的安排，这里只验证程序流程而不代表真实试听评价[R2]。"
                candidates = request.input["candidates"]
                assert all("primary_genres" not in c["album"] for c in candidates)
                assert all("catalog_genre_hints" in c for c in candidates)
                return CallResult(
                    {
                        "assessments": [
                            {
                                "c": c["c"],
                                "reason": "NO_MUSIC_DESCRIPTION"
                                if i < insufficient
                                else "SUFFICIENT",
                                "refs": ["R1", "R2"],
                            }
                            for i, c in enumerate(candidates)
                        ],
                        "cards": [
                            {"c": c["c"], "score": 4, "text": text, "refs": ["R1", "R2"]}
                            for c in (candidates[insufficient:] if insufficient else candidates[:3])
                        ],
                    },
                    usage=Usage(10, 10, 0),
                )

            harness.adapter.replies = reply
            harness.control.register(harness.request)
            _, execution = await harness.supervisor.accept(
                harness.request, pipeline, harness.control
            )
            if background:
                await harness.supervisor.wait(execution.id)
                entry = harness.control.executions[execution.id]
                first_ids = set(entry["display"]["shown_candidate_ids"])
                assert seen == ["batch_v6"]
                if cold:
                    deferred = [
                        screening
                        for (round_id, identifier), screening in harness.control.screenings.items()
                        if identifier not in first_ids and identifier != "album-1"
                    ]
                    assert len(deferred) == 1
                    assert deferred[0]["disposition"] == "DEFERRED"
                request = harness.control.continuation(
                    execution.id,
                    new_execution_id="fast-continue",
                    python_boot_id=harness.supervisor.boot_id,
                    run_id="fast-supply-run",
                )
                harness.control.register(request)
                batch = BatchResearchStage(
                    harness.model, harness.tools, evidence, genre_names={"jazz": "爵士"}
                )
                pipeline = RoundPipeline(
                    base.preferences,
                    base.recall,
                    batch,
                    BatchWriteStage(harness.model, harness.tools, batch=batch),
                    BatchSelectStage(harness.model, batch=batch),
                    generate_long_reviews=False,
                )
                _, execution = await harness.supervisor.accept(request, pipeline, harness.control)
            await harness.supervisor.wait(execution.id)
            if background:
                supply = next(
                    d["payload"]
                    for d in harness.control.deliveries.values()
                    if d["kind"] == "CARD_BATCH" and d["payload"]["batch_kind"] == "SUPPLY"
                )
                assert len(supply["cards"]) == 1
                assert not first_ids.intersection(c["candidate_id"] for c in supply["cards"])
                assert not any(
                    d["kind"] == "LONG_REVIEW" for d in harness.control.deliveries.values()
                )
            assert execution.status == "CLOSED", execution.error_code
            if background:
                supply_states = [
                    d["payload"]["state"]
                    for d in harness.control.deliveries.values()
                    if d["kind"] == "SUPPLY_STATUS"
                ]
                assert supply_states[-1] == "R_EXHAUSTED"
            assert seen == (["batch_v6", "batch_v6"] if background else ["batch_v6"])
            assert any(
                call["plan"]["purpose"] == "SELECT" for call in harness.control.invocations.values()
            )
            first = next(
                d["payload"]
                for d in harness.control.deliveries.values()
                if d["kind"] == "CARD_BATCH"
            )
            assert first["analysis_mode"] == ("BASIC" if insufficient == 4 else "PERSONALIZED")
            assert len(first["cards"]) == (min(3, 4 - insufficient))
            if insufficient:
                assert (
                    sum(
                        a["reason"] == "NO_MUSIC_DESCRIPTION"
                        for a in batch.statistics["evidence_assessments"].values()
                    )
                    == insufficient
                )
                assert len(seen) == 1
                rejected = [
                    screening
                    for screening in harness.control.screenings.values()
                    if screening["reason_code"] == "NO_MUSIC_DESCRIPTION"
                ]
                assert len(rejected) == insufficient
            assert all(c["admission"]["audience"]["listeners"] >= minimum for c in first["cards"])
            assert "album-1" not in {c["candidate_id"] for c in first["cards"]}
            if insufficient == 4:
                assert first["outcome"] == "NO_MATCH"
                return
            entry = harness.control.executions[execution.id]
            tampered = dict(
                first["cards"][0], admission={**first["cards"][0]["admission"], "audience": None}
            )
            assert not harness.control._audience_permits(entry, tampered)
        finally:
            harness.close()

    asyncio.run(run())


def test_audience_counts_distinct_listeners_not_plays(tmp_path):
    from auralis_intelligence.recall import WeightConfig, load_a_v2

    fixture = tmp_path / "data.json"
    fixture.write_text(
        json.dumps(
            {
                "format_version": 1,
                "dataset_id": "audience-test",
                "window": "all",
                "album_ids": ["a", "b", "c"],
                "tag_ids": ["rock"],
                "tag_parents": [],
                "album_tags": [[1], [1], [1]],
                "interactions": [[500, 0, 0], [2, 8, 0], [0, 9, 0]],
            }
        )
    )
    engine = load_a_v2(fixture, weights=WeightConfig())
    result = engine.training_audience(["a", "b", "c", "missing"])
    assert result["counts"] == {"a": 2, "b": 2, "c": 0, "missing": None}
    assert result["sample_listeners"] == 3 and result["window"] == "all"


def test_vectorized_reciprocal_ranking_preserves_id_ties():
    import numpy as np
    from auralis_intelligence.recall.neighborhood import NeighborhoodModel

    model = NeighborhoodModel()
    model.data = SimpleNamespace(album_ids=("z", "b", "a", "c", "d", "e"))
    model._id_order = np.argsort(np.asarray(model.data.album_ids))
    scores = np.array([2.0, 1.0, 1.0, 0.0, -np.inf, 2.0])
    expected = np.zeros(6)
    ids = sorted(np.flatnonzero(scores > 0), key=lambda i: (-scores[i], model.data.album_ids[i]))
    for rank, i in enumerate(ids, 1):
        expected[i] = 1.0 / (model.config.rrf_constant + rank)
    np.testing.assert_array_equal(model._reciprocal_ranks(scores), expected)


def test_prepared_evidence_digest_age_and_original_observation(tmp_path):
    from datetime import datetime, timedelta, timezone
    from hashlib import sha256

    from auralis_intelligence.album_reviews.prepared import PreparedEvidence

    source = {
        "url": "https://one.example/review",
        "text": "An actual saved test page.",
        "read_at": (datetime.now(timezone.utc) - timedelta(hours=1))
        .isoformat()
        .replace("+00:00", "Z"),
        "sha256": "wrong",
    }
    path = tmp_path / "evidence.json"

    def save():
        path.write_text(
            json.dumps({"format": "prepared-evidence.v1", "albums": {"a": {"sources": [source]}}})
        )

    save()
    with pytest.raises(ValueError, match="DIGEST"):
        PreparedEvidence(path)
    source["sha256"] = sha256(source["text"].encode()).hexdigest()
    save()
    store = PreparedEvidence(path)
    response = asyncio.run(store.fetch(source["url"], 1000, 1))
    assert response.retrieval_mode == "PREPARED_EXCERPT"
    assert response.observed_at == source["read_at"]
    assert response.body.decode() == source["text"]
    source["read_at"] = (
        (datetime.now(timezone.utc) - timedelta(days=8)).isoformat().replace("+00:00", "Z")
    )
    save()
    assert PreparedEvidence(path).sources("a") == []


def test_research_window_covers_requested_directions_before_artist_only_fill():
    batch = BatchResearchStage(None, None, SimpleNamespace(), candidate_limit=3)
    candidates = [
        {
            "release_group_id": str(i),
            "identity": {"artist_credit": f"Artist {i}", "primary_genres": [{"genre_id": genre}]},
        }
        for i, genre in enumerate(["hyperpop", "hyperpop", "hyperpop", "post-rock", "trip-hop"])
    ]
    preferences = {"tags": [{"id": genre} for genre in ["hyperpop", "post-rock", "trip-hop"]]}
    assert [c["release_group_id"] for c in batch._research_window(candidates, preferences)] == [
        "0",
        "3",
        "4",
    ]
    assert [c["release_group_id"] for c in batch._research_window(candidates, {"tags": []})] == [
        "0",
        "1",
        "2",
    ]
    # Missing coverage never invents candidates or enlarges the research budget.
    preferences["tags"].append({"id": "missing"})
    assert len(batch._research_window(candidates, preferences)) == 3
