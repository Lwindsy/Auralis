import asyncio
from types import SimpleNamespace

from auralisy_intelligence.album_reviews.batch import BatchWriteStage
from auralisy_intelligence.album_reviews.cold import ColdEvidence
from auralisy_intelligence.album_reviews.types import AlbumDossier, ReadRecord
from auralisy_intelligence.providers.types import ReadResult, SearchHit


def test_first_read_returns_while_slow_page_continues_and_long_collects_both():
    async def run():
        slow_started, release = asyncio.Event(), asyncio.Event()
        children = []

        def spawn(fn):
            task = asyncio.create_task(fn())
            children.append(task)
            return task

        execution = SimpleNamespace(spawn=spawn)

        class Tools:
            domain = staticmethod(lambda host: SimpleNamespace(top_domain_under_public_suffix=host))

            async def search(self, *args, **kwargs):
                return (
                    SearchHit("https://fast.example/a", "Artist Album"),
                    SearchHit("https://slow.example/a", "Artist Album"),
                )

            async def read(self, execution, *, url, **kwargs):
                if "slow" in url:
                    slow_started.set()
                    await release.wait()
                return ReadResult(
                    {
                        "status": "READ_OK",
                        "failure_reason": None,
                        "read_id": url,
                        "content_digest": url,
                        "publisher_key": url,
                    },
                    "Artist Album music rhythms " * 30,
                )

        evidence = ColdEvidence(overlap=True, sources_per_album=1, race=True)
        evidence.keep_background = True
        try:
            await asyncio.wait_for(
                evidence.prepare(
                    execution,
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
            await slow_started.wait()
            first = evidence.take_reads("a")
            assert len(first) == 1 and not release.is_set()
            dossier = AlbumDossier("a", {})
            dossier.reads.append(ReadRecord("R1", first[0], first[0].text, True))
            writer = BatchWriteStage(None, None, batch=SimpleNamespace(evidence=evidence))
            pending = asyncio.create_task(writer.additional_records(execution, dossier))
            await asyncio.sleep(0)
            assert not pending.done()
            release.set()
            extras = await asyncio.wait_for(pending, 1)
            assert len(extras) == 1 and extras[0].ref == "R2" and extras[0].excerpt
            assert not extras[0].result.text
            assert len(dossier.reads) == 2
            assert not evidence.background
        finally:
            for task in children:
                task.cancel()
            await asyncio.gather(*children, return_exceptions=True)

    asyncio.run(run())


def test_discard_unselected_cancels_and_drains_pending_reads():
    async def run():
        stopped = asyncio.Event()
        children = []

        def spawn(fn):
            task = asyncio.create_task(fn())
            children.append(task)
            return task

        class Tools:
            domain = staticmethod(lambda host: SimpleNamespace(top_domain_under_public_suffix=host))

            async def search(self, *args, **kwargs):
                return (
                    SearchHit("https://fast.example/a", "Artist Album"),
                    SearchHit("https://slow.example/a", "Artist Album"),
                )

            async def read(self, execution, *, url, **kwargs):
                if "slow" in url:
                    try:
                        await asyncio.Event().wait()
                    finally:
                        stopped.set()
                return ReadResult(
                    {"status": "READ_OK", "failure_reason": None},
                    "Artist Album music rhythms " * 30,
                )

        evidence = ColdEvidence(overlap=True, sources_per_album=1, race=True)
        evidence.keep_background = True
        await evidence.prepare(
            SimpleNamespace(spawn=spawn),
            [{"release_group_id": "a", "identity": {"title": "Album", "artist_credit": "Artist"}}],
            Tools(),
        )
        await evidence.discard_unselected(set())
        assert stopped.is_set() and all(task.done() for task in children)
        assert not evidence.background

    asyncio.run(run())


def test_long_generation_receives_first_and_additional_records():
    from auralisy_intelligence.agents import CallResult
    from auralisy_intelligence.album_reviews.writing import WriteStage

    async def run():
        first = ReadRecord("R1", ReadResult({"read_id": "first"}, ""), "first actual excerpt", True)
        second = ReadRecord(
            "R2", ReadResult({"read_id": "second"}, ""), "second actual excerpt", True
        )
        dossier = AlbumDossier("a", {}, reads=[first], screening={"qualified_read_ids": ["first"]})

        class Writer(WriteStage):
            async def additional_records(self, execution, dossier):
                return [second]

            async def _response(self, execution, dossier, preferences, records, *args, **kwargs):
                assert [r.excerpt for r in records] == [
                    "first actual excerpt",
                    "second actual excerpt",
                ]
                return CallResult(
                    {"status": "ok", "sections": {"mood": "合成结果[R1][R2]"}, "refs": ["R1", "R2"]}
                )

        writer = Writer(None, None)
        execution = SimpleNamespace(
            start={
                "agent_policy": {
                    "writing": {
                        "long_tool_turns": 0,
                        "long_extra_max_searches": 0,
                        "long_extra_max_reads": 0,
                    }
                }
            }
        )
        result = await writer._long_work(
            execution, dossier, {}, {"extra_read_ids": [], "invocation_ids": []}, 3
        )
        assert result["status"] == "READY"
        assert result["extra_read_ids"] == ["second"]
        assert result["citations"] == [
            {"marker": "R1", "read_id": "first"},
            {"marker": "R2", "read_id": "second"},
        ]

    asyncio.run(run())
