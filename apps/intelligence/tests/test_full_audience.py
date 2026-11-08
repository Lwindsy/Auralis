"""Product admission counts must be independent of the training split."""

import json
from uuid import UUID

import pytest
from auralis_intelligence.contracts import content_digest
from auralis_intelligence.recall import Preference, RecallRequest, WeightConfig, load_a_v2
from auralis_intelligence.recall.full_audience import FullAudience, matches_policy


def test_full_population_changes_eligibility_not_training_scores(tmp_path):
    ids = [str(UUID(int=i)) for i in (1, 2, 3)]
    train = tmp_path / "train.json"
    train.write_text(
        json.dumps(
            {
                "format_version": 1,
                "dataset_id": "train",
                "window": "0",
                "album_ids": ids,
                "tag_ids": ["rock"],
                "tag_parents": [],
                "album_tags": [[1], [1], [1]],
                "interactions": [[1, 0, 1], [0, 1, 1]],
            }
        )
    )
    engine = load_a_v2(train, weights=WeightConfig())
    request = RecallRequest(limit=2, tags=(Preference("rock"),))
    before = engine.recall(request)
    with pytest.raises(ValueError, match="FULL_AUDIENCE_REQUIRED"):
        engine.recall(request, min_listeners=50)
    artifact = {
        "format_version": 2,
        "scope": "FULL_CATALOG",
        "source": "LISTENBRAINZ_SAMPLE",
        "catalog_dataset_id": 1,
        "source_manifest_sha256": "a" * 64,
        "dataset_id": "core:" + "a" * 64,
        "window": "0",
        "sample_listeners": 100,
        "counts": [{"release_group_id": i, "listeners": n} for i, n in zip(ids, [50, 60, 49])],
    }
    artifact["digest"] = content_digest(artifact, "digest")
    path = tmp_path / "full.json"
    path.write_text(json.dumps(artifact))
    binding = {k: artifact[k] for k in ("dataset_id", "window", "sample_listeners")} | {
        "snapshot_digest": artifact["digest"]
    }
    full = FullAudience(path, binding)
    engine.bind_full_audience(full)
    assert engine.recall(request) == before
    result = engine.recall(request, min_listeners=50)
    assert {row.album_id for row in result.candidates} == set(ids[:2])
    assert engine.training_audience(ids)["counts"][ids[0]] == 1
    assert engine.audience(ids + ["missing"])["counts"] == dict(
        zip(ids + ["missing"], [50, 60, 49, None])
    )
    for field, value in (
        ("window", "2025"),
        ("dataset_id", "train"),
        ("snapshot_digest", "sha256:" + "b" * 64),
    ):
        with pytest.raises(ValueError):
            FullAudience(path, binding | {field: value})
    artifact["counts"][0]["listeners"] = 99
    path.write_text(json.dumps(artifact))
    with pytest.raises(ValueError):
        FullAudience(path, binding)


def test_admission_never_uses_training_binding():
    audience = {
        "source": "LISTENBRAINZ_SAMPLE",
        "dataset_id": "full",
        "window": "0",
        "sample_listeners": 100,
        "listeners": 50,
    }
    binding = {
        "dataset_id": "full",
        "window": "0",
        "sample_listeners": 100,
        "snapshot_digest": "sha256:" + "a" * 64,
    }
    assert matches_policy(audience, {"audience_binding": binding})
    assert not matches_policy(audience, {})
    assert not matches_policy(audience | {"dataset_id": "train"}, {"audience_binding": binding})
    assert not matches_policy(audience | {"listeners": None}, {"audience_binding": binding})


def test_java_redirect_proof_preserves_original_rank():
    import asyncio
    from types import SimpleNamespace

    from auralis_intelligence.agents.runtime import Execution
    from auralis_intelligence.contracts import digest
    from test_agent_runtime import start

    async def run():
        request = start()
        proposal = {
            "effective_preference_digest": digest("prefs"),
            "candidates": [{"release_group_id": "old", "recall_rank": 1}],
        }
        accepted = [
            {
                "release_group_id": "canonical",
                "proposed_release_group_id": "old",
                "recall_rank": 1,
                "known_review_refs": [],
                "identity": {
                    "title": "Album",
                    "artist_credit": "Artist",
                    "first_release_year": None,
                    "release_type": None,
                    "primary_genres": [],
                },
            }
        ]
        binding = {
            "candidate_bundle_digest": digest(accepted),
            "effective_preference_digest": proposal["effective_preference_digest"],
            "exclusion_digest": request["input"]["exclusion_digest"],
            "selection_config_revision": request["selection_config"]["revision"],
            "agent_policy_revision": request["agent_policy"]["revision"],
            "recall_binding": request["recall_binding"],
        }
        bundle = {
            "candidate_bundle_id": "bundle",
            "digest": digest(accepted),
            "binding": binding,
            "accepted": accepted,
            "rejected": [],
            "counts": {"proposed": 1, "accepted": 1, "rejected": 0},
        }

        async def reply(*args):
            return bundle

        execution = Execution(request, SimpleNamespace(request=reply))
        await execution.control_request("candidate-validation", proposal)
        assert list(execution.candidates) == ["canonical"]
        accepted[0]["proposed_release_group_id"] = "never-proposed"
        bundle["digest"] = binding["candidate_bundle_digest"] = digest(accepted)
        with pytest.raises(ValueError):
            await execution.control_request("candidate-validation", proposal)

    asyncio.run(run())
