"""Live discovery starts empty and overlaps searches with cancellable reads."""

import asyncio
from types import SimpleNamespace

from auralis_intelligence.album_reviews.cold import ColdEvidence
from auralis_intelligence.providers.types import ReadResult, SearchHit


def test_overlapping_search_and_read_does_not_wait_for_slow_search():
    async def run():
        read_started, release_search = asyncio.Event(), asyncio.Event()
        candidates = [
            {"release_group_id": i, "identity": {"title": "Album " + i, "artist_credit": "Artist"}}
            for i in ["a", "b"]
        ]

        class Tools:
            domain = staticmethod(lambda host: SimpleNamespace(top_domain_under_public_suffix=host))

            async def search(self, execution, *, candidate_id, **kwargs):
                if candidate_id == "b":
                    await release_search.wait()
                return (
                    SearchHit(
                        "https://one.example/artist-album-" + candidate_id,
                        "Artist Album " + candidate_id,
                    ),
                )

            async def read(self, execution, *, candidate_id, **kwargs):
                read_started.set()
                return ReadResult(
                    {"status": "READ_OK", "failure_reason": None},
                    ("Artist Album " + candidate_id + " melody and drums ") * 30,
                )

        evidence = ColdEvidence(overlap=True, sources_per_album=1)
        assert evidence.albums == {} and evidence.sources("a") == []
        task = asyncio.create_task(evidence.prepare(None, candidates, Tools()))
        await asyncio.wait_for(read_started.wait(), 1)
        assert not release_search.is_set() and not task.done()
        release_search.set()
        await task
        assert evidence.take_reads("a")[0].text
        assert evidence.albums == {}

    asyncio.run(run())


def test_race_cancels_slow_page_after_usable_body_and_clears_no_hidden_cache():
    async def run():
        cancelled = asyncio.Event()

        class Tools:
            domain = staticmethod(lambda host: SimpleNamespace(top_domain_under_public_suffix=host))

            async def search(self, *args, **kwargs):
                return (
                    SearchHit("https://slow.example/artist-album", "Artist Album"),
                    SearchHit("https://fast.example/artist-album", "Artist Album"),
                )

            async def read(self, execution, *, url, **kwargs):
                if "slow" in url:
                    try:
                        await asyncio.Event().wait()
                    finally:
                        cancelled.set()
                return ReadResult(
                    {"status": "READ_OK", "failure_reason": None}, "Artist Album music drums " * 30
                )

        evidence = ColdEvidence(overlap=True, sources_per_album=1, race=True)
        await asyncio.wait_for(
            evidence.prepare(
                None,
                [
                    {
                        "release_group_id": "a",
                        "identity": {"title": "Album", "artist_credit": "Artist"},
                    }
                ],
                Tools(),
            ),
            1,
        )
        assert cancelled.is_set()
        assert len(evidence.take_reads("a")) == 1
        assert evidence.take_reads("a") is None
        assert any(e.get("status") == "CANCELLED" for e in evidence.events)

    asyncio.run(run())


def test_audience_filter_is_applied_before_top_count(tmp_path):
    import json

    from auralis_intelligence.recall import Preference, RecallRequest, WeightConfig, load_a_v2

    path = tmp_path / "snapshot.json"
    path.write_text(
        json.dumps(
            {
                "format_version": 1,
                "dataset_id": "test",
                "window": "all",
                "album_ids": ["a", "b", "c"],
                "tag_ids": ["rock"],
                "tag_parents": [],
                "album_tags": [[1], [1], [1]],
                "interactions": [[1, 1, 1], [1, 0, 1]],
            }
        )
    )
    engine = load_a_v2(path, weights=WeightConfig())
    result = engine.recall(RecallRequest(limit=2, tags=(Preference("rock"),)), min_listeners=2)
    assert [row.album_id for row in result.candidates] == ["a", "c"]
