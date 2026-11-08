"""Explicit child interests exempt their subtree; hard and independent dislikes still apply."""

import json
from pathlib import Path
from types import SimpleNamespace
from uuid import UUID

import numpy as np
from auralis_intelligence.agents.recall_stage import recall_request
from auralis_intelligence.contracts import content_digest
from auralis_intelligence.recall import Preference, RecallRequest, WeightConfig
from auralis_intelligence.recall.common import recall
from auralis_intelligence.recall.io import load_request
from auralis_intelligence.recall.types import RecallDataset
from scipy import sparse
from test_recall_stage import preferences


def test_shared_hierarchy_cases_filter_before_top_n(tmp_path):
    fixture = json.loads(
        (Path(__file__).parent / "fixtures/genre-exception-cases.json").read_text()
    )
    names = [row["name"] for row in fixture["albums"]]
    matrix = [[int(g in row["genres"]) for g in fixture["genres"]] for row in fixture["albums"]]
    data = RecallDataset(
        tuple(str(UUID(int=i + 1)) for i in range(len(names))),
        tuple(fixture["genres"]),
        sparse.csr_matrix(matrix),
        sparse.csr_matrix(np.ones((2, len(names)))),
        "train",
        "0",
        tuple(map(tuple, fixture["parents"])),
    )
    model = SimpleNamespace(
        data=data,
        weights=WeightConfig(),
        name="synthetic",
        score=lambda request: np.ones(len(names)),
    )
    for case in fixture["cases"]:
        request = RecallRequest(
            limit=len(names),
            tags=(Preference("cool"),),
            disliked_tags=tuple(case["disliked"]),
            forbidden_tags=tuple(case["forbidden"]),
            disliked_exceptions=tuple(case["positive"]),
        )
        actual = recall(model, request)
        assert [names[data.album_ids.index(c.album_id)] for c in actual.candidates] == case[
            "accepted"
        ]
    request_file = tmp_path / "request.json"
    request_file.write_text(
        json.dumps(
            {
                "limit": 1,
                "tags": [{"id": "cool"}],
                "disliked_tags": ["jazz"],
                "disliked_exceptions": ["cool"],
            }
        )
    )
    assert recall(model, load_request(request_file)).candidates[0].album_id == data.album_ids[1]


def test_only_explicit_positive_overrides_become_exception_roots():
    snapshot = preferences()
    snapshot["overrides"] = [
        {"genre_id": "cool", "action": action, "text_span": {"start": 0, "end": 1}}
        for action in ("PREFER", "REQUIRE", "AVOID", "EXCLUDE")
    ]
    snapshot["effective_preference_digest"] = content_digest(
        snapshot, "effective_preference_digest"
    )
    assert recall_request(snapshot, [], 20).disliked_exceptions == ("cool",)
    snapshot["overrides"] = []
    snapshot["effective_preference_digest"] = content_digest(
        snapshot, "effective_preference_digest"
    )
    assert recall_request(snapshot, [], 20).disliked_exceptions == ()
