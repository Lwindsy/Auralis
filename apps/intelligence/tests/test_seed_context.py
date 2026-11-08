"""Real control-bound seed caching, isolation and fresh/continued execution."""

import asyncio
import re
from copy import deepcopy

import pytest
from auralis_intelligence.agents import CallResult, Execution, Usage
from auralis_intelligence.agents.demo import DemoHarness, DemoPages
from auralis_intelligence.agents.http import ControlHTTPClient, create_control_double_app
from auralis_intelligence.album_reviews.seed_context import SeedContext, SeededPipeline
from auralis_intelligence.contracts import ContractError, content_digest
from auralis_intelligence.preferences import PreferencesStage
from auralis_intelligence.providers import ToolGateway
from auralis_intelligence.providers.testing import StaticSearchAdapter
from auralis_intelligence.providers.types import SearchHit
from test_first_batch import running


class Search(StaticSearchAdapter):
    def __init__(self):
        super().__init__(())
        self.calls = []

    async def search(self, binding, query, allowed_domains, blocked_domains=()):
        identifier = re.search(r"album-\d+", query)[0]
        self.calls.append(identifier)
        return CallResult(
            (SearchHit(f"https://one.example/{identifier}", query),), usage=Usage(1, 1, 0)
        )


async def setup(harness, request=None, control=None, search=None):
    request = request or deepcopy(harness.request)
    harness.control.register(request)
    ex = Execution(request, control or harness.control)
    await PreferencesStage(harness.model, harness.reference)(ex)
    search = search or Search()
    tools = ToolGateway(DemoPages(), search_adapter=search, model_gateway=harness.model)
    seeds = SeedContext(harness.model, tools)
    seeds.start(ex)
    await seeds.task
    return ex, seeds, search


def next_request(harness, ex, *, session=None):
    request = deepcopy(harness.request)
    request.update(
        execution_id="next-execution", round_id="next-round", round_seq=2, run_id="next-run"
    )
    request["budget"]["round_budget_scope_id"] = "next-round-budget"
    request["input"].update(
        raw=None, accepted_preferences=deepcopy(ex.start["input"]["accepted_preferences"])
    )
    if session:
        request["session_id"] = session
        request["budget"]["session_budget_scope_id"] = session + "-budget"
    request["payload_digest"] = content_digest(request)
    return request


def test_seed_cache_reused_in_same_execution_and_next_round_over_http(tmp_path):
    async def run():
        h = DemoHarness(scenario="album", root=tmp_path, r=5, k=3, n=3)
        try:
            async with running(create_control_double_app(h.control, token="seed-test")) as url:
                client = ControlHTTPClient(url, "seed-test")
                try:
                    ex, seeds, search = await setup(h, control=client)
                    assert search.calls == ["album-0"] and len(seeds.rows) == 1
                    saved = deepcopy(seeds.rows)
                    h.adapter.replies = lambda *_: CallResult({"ok": True}, usage=Usage(1, 1, 0))
                    args = dict(
                        role="select",
                        purpose="SCREEN",
                        system="Test",
                        input={},
                        output_schema={
                            "type": "object",
                            "properties": {"ok": {"type": "boolean"}},
                            "required": ["ok"],
                            "additionalProperties": False,
                        },
                        prompt_version="test.v1",
                    )
                    await seeds.generate(ex, **args)
                    await seeds.generate(ex, **args)
                    assert search.calls == ["album-0"]
                    request = next_request(h, ex)
                    await seeds.close()
                    await ex.cleanup()
                    second, reused, _ = await setup(h, request, client, search)
                    assert reused.rows == saved and reused.reused_count == 1
                    assert search.calls == ["album-0"]
                    await reused.generate(second, **args)
                    assert (
                        h.adapter.requests[-1][1].input["seed_evidence"][0]["seed_id"] == "album-0"
                    )
                    assert any(
                        i["plan"]["read_ids"] == [saved[0]["sources"][0]["read_id"]]
                        for i in h.control.invocations.values()
                        if i["execution_id"] == second.id
                    )
                    await reused.close()
                    await second.cleanup()
                finally:
                    await client.close()
        finally:
            h.close()

    asyncio.run(run())


def test_only_new_seeds_are_fetched_and_sessions_are_isolated(tmp_path):
    async def run():
        h = DemoHarness(scenario="album", root=tmp_path)
        try:
            ex, seeds, search = await setup(h)
            first_id = seeds.rows[0]["sources"][0]["read_id"]
            request = deepcopy(h.request)
            request["execution_id"] = "edited"
            request["round_id"] = "edited-round"
            request["budget"]["round_budget_scope_id"] = "edited-round-budget"
            raw = request["input"]["raw"]
            raw["liked_albums"].append(
                {
                    "release_group_id": "album-1",
                    "external": None,
                    "strength": "normal",
                    "focused": False,
                }
            )
            raw["input_digest"] = content_digest(raw, "input_digest")
            request["payload_digest"] = content_digest(request)
            edited, added, _ = await setup(h, request, search=search)
            assert search.calls == ["album-0", "album-1"]
            assert added.reused_count == 1 and added.fetched_count == 1
            plan = edited.invocation_plan(
                "SCREEN",
                "screen",
                {},
                read_ids=(first_id,),
                max_input_tokens=100,
                max_output_tokens=100,
            )
            grant = await edited.control_request("invocation-grants", {"items": [plan]})
            assert grant["items"][0]["decision"] == "GRANTED"
            other = next_request(h, ex, session="other-session")
            other_ex, isolated, _ = await setup(h, other, search=search)
            assert search.calls == ["album-0", "album-1", "album-0"]
            assert isolated.rows[0]["sources"][0]["read_id"] != first_id
            for x, ctx in [(ex, seeds), (edited, added), (other_ex, isolated)]:
                await ctx.close()
                await x.cleanup()
        finally:
            h.close()

    asyncio.run(run())


@pytest.mark.parametrize("change", ["digest", "seed", "url"])
def test_seed_cache_rejects_forged_data(tmp_path, change):
    async def run():
        h = DemoHarness(scenario="album", root=tmp_path)
        try:
            ex, seeds, _ = await setup(h)
            row = deepcopy(seeds.rows[0])
            if change == "digest":
                row["sources"][0]["text"] += "forged"
            elif change == "seed":
                row["seed_id"] = "album-1"
            else:
                row["sources"][0]["url"] = "https://other.example/fake"
            with pytest.raises(ContractError):
                await ex.control_request("seed-evidence-save", {"items": [row]})
            await seeds.close()
            await ex.cleanup()
        finally:
            h.close()

    asyncio.run(run())


def test_seed_state_cleared_with_session_and_late_save_rejected(tmp_path):
    async def run():
        h = DemoHarness(scenario="album", root=tmp_path)
        try:
            ex, seeds, _ = await setup(h)
            saved = deepcopy(seeds.rows)
            h.control.delete_session(ex.start["session_id"])
            assert not h.control.seed_cache and not h.control.reads
            with pytest.raises(ContractError, match="EXECUTION_GONE"):
                h.control.register(next_request(h, ex))
            with pytest.raises(ContractError, match="EXECUTION_GONE"):
                await ex.control_request("seed-evidence-save", {"items": saved})
            await seeds.close()
            await ex.cleanup()
        finally:
            h.close()

    asyncio.run(run())


def test_seed_pipeline_starts_in_parallel_and_drains_on_cancel():
    async def run():
        started, stopped = asyncio.Event(), asyncio.Event()

        class Seeds:
            rows = []

            def start(self, execution):
                async def collect():
                    started.set()
                    try:
                        await asyncio.Event().wait()
                    finally:
                        stopped.set()

                self.task = asyncio.create_task(collect())

            async def close(self):
                self.task.cancel()
                await asyncio.gather(self.task, return_exceptions=True)

        async def pipeline(execution):
            await started.wait()
            raise asyncio.CancelledError

        with pytest.raises(asyncio.CancelledError):
            await SeededPipeline(pipeline, Seeds())(None)
        assert stopped.is_set()

    asyncio.run(run())


def test_real_round_continuation_reuses_seed_and_writes_with_same_evidence(tmp_path):
    from dataclasses import replace

    from auralis_intelligence.agents import RoundSupervisor
    from auralis_intelligence.agents.demo import _reply

    async def run():
        h = DemoHarness(scenario="album", root=tmp_path, r=5, k=3, n=1)
        try:
            h.request["deadlines"]["idle_close_ms"] = 20
            h.request["payload_digest"] = content_digest(h.request)
            h.adapter.replies = lambda binding, request: _reply(
                binding, replace(request, schema_name=request.schema_name.removesuffix("_seed_v1"))
            )
            search = Search()

            def pipeline(request):
                seeds = SeedContext(
                    h.model, ToolGateway(DemoPages(), search_adapter=search, model_gateway=h.model)
                )
                inner = h.pipeline(request, background=True)
                inner.research.model = inner.writing.model = inner.selection.model = seeds
                return SeededPipeline(inner, seeds)

            h.control.register(h.request)
            _, first = await h.supervisor.accept(h.request, pipeline(h.request), h.control)
            await h.supervisor.wait(first.id)
            assert first.status == "CLOSED", first.error_code
            assert search.calls == ["album-0"]
            saved = deepcopy(h.control.seed_cache)
            shown = h.control.executions[first.id]["display"]["shown_candidate_ids"]
            assert shown
            resumed = h.control.continuation(
                first.id,
                new_execution_id="continued",
                python_boot_id="new-python",
                regenerate=shown[:1],
            )
            h.control.register(resumed)
            supervisor = RoundSupervisor(boot_id="new-python")
            _, second = await supervisor.accept(resumed, pipeline(resumed), h.control)
            await supervisor.wait(second.id)
            assert second.status == "CLOSED", second.error_code
            assert search.calls == ["album-0"]
            assert h.control.seed_cache == saved
            assert all(
                request.input.get("seed_evidence")
                for _, request in h.adapter.requests
                if request.schema_name.startswith("long_review")
            )
        finally:
            h.close()

    asyncio.run(run())


def test_missing_seed_page_is_remembered_and_no_seed_input_never_searches(tmp_path):
    async def run():
        h = DemoHarness(scenario="album", root=tmp_path)
        try:
            empty = StaticSearchAdapter(())
            ex, seeds, _ = await setup(h, search=empty)
            assert not seeds.rows and seeds.status["album-0"] == "NO_USABLE_PAGE"
            request = next_request(h, ex)
            search = Search()
            second, reused, _ = await setup(h, request, search=search)
            assert not search.calls and reused.reused_count == 1
            h.control.delete_session(ex.start["session_id"])
            for x, ctx in [(ex, seeds), (second, reused)]:
                await ctx.close()
                await x.cleanup()
        finally:
            h.close()
        h = DemoHarness(scenario="genre", root=tmp_path)
        try:
            ex, seeds, search = await setup(h)
            assert not search.calls and not seeds.rows
            await seeds.close()
            await ex.cleanup()
        finally:
            h.close()

    asyncio.run(run())


def test_seeded_batch_and_long_review_use_same_registered_seed(tmp_path):
    from auralis_intelligence.agents.pipeline import FirstBatchPipeline
    from auralis_intelligence.album_reviews.batch import (
        BatchResearchStage,
        BatchSelectStage,
        BatchWriteStage,
    )
    from auralis_intelligence.album_reviews.cold import ColdEvidence

    async def run():
        h = DemoHarness(scenario="album", root=tmp_path, r=5, k=3, n=3)
        try:
            policy = h.request["agent_policy"]
            policy["audience_min_listeners"] = 50
            policy["audience_binding"] = {
                "dataset_id": "full-synthetic",
                "window": "0",
                "sample_listeners": 1000,
                "snapshot_digest": "sha256:" + "a" * 64,
            }
            policy["digest"] = content_digest(policy, "digest")
            h.request["payload_digest"] = content_digest(h.request)
            for identifier in h.control.catalog:
                h.control.candidate_audiences[identifier] = {
                    "source": "LISTENBRAINZ_SAMPLE",
                    "dataset_id": "full-synthetic",
                    "window": "0",
                    "sample_listeners": 1000,
                    "listeners": 50,
                }
            text = "这张专辑以克制的编配展开，钢琴与轻缓鼓点之间留有清晰空间，旋律渐进而不过度依赖厚重音墙。人声与吉他显得亲近，适合深夜安静地听；这里只验证合成资料如何进入程序，不代表实际试听或真实音乐质量评价[R1]。"

            def reply(binding, request):
                assert request.input["seed_evidence"][0]["seed_id"] == "album-0"
                if request.schema_name == "batch_v3_seed_v1":
                    candidates = request.input["candidates"]
                    output = {
                        "assessments": [
                            {"c": c["c"], "reason": "SUFFICIENT", "refs": ["R1"]}
                            for c in candidates
                        ],
                        "cards": [
                            {"c": c["c"], "score": 4, "text": text, "refs": ["R1"]}
                            for c in candidates[:3]
                        ],
                    }
                else:
                    assert request.schema_name == "long_review_v1_2_seed_v1"
                    output = {
                        "status": "ok",
                        "sections": {
                            "production": "",
                            "instrumentation": "",
                            "vocals": "",
                            "mood": text,
                        },
                        "refs": ["R1"],
                        "missing": [],
                        "tool_requests": [],
                    }
                return CallResult(output, usage=Usage(1, 1, 0))

            h.adapter.replies = reply
            search = Search()
            tools = ToolGateway(DemoPages(), search_adapter=search, model_gateway=h.model)
            seeds = SeedContext(h.model, tools)
            batch = BatchResearchStage(
                seeds,
                tools,
                ColdEvidence(overlap=True, sources_per_album=1, race=True),
                candidate_limit=3,
            )
            base = h.pipeline(h.request)
            pipeline = SeededPipeline(
                FirstBatchPipeline(
                    base.preferences,
                    base.recall,
                    batch,
                    BatchWriteStage(seeds, tools, batch=batch),
                    BatchSelectStage(seeds, batch=batch),
                ),
                seeds,
            )
            h.control.register(h.request)
            _, ex = await h.supervisor.accept(h.request, pipeline, h.control)
            await h.supervisor.wait(ex.id)
            assert ex.status == "CLOSED", ex.error_code
            cards = next(
                d["payload"]["cards"]
                for d in h.control.deliveries.values()
                if d["kind"] == "CARD_BATCH"
            )
            assert len(cards) == 3
            assert all(c["short_review"]["prompt_version"] == "batch.v3.seed.v1" for c in cards)
            longs = [
                d["payload"] for d in h.control.deliveries.values() if d["kind"] == "LONG_REVIEW"
            ]
            assert len(longs) == 3 and all(
                row["status"] == "READY" and row["prompt_version"] == "long_review.v1.2.seed.v1"
                for row in longs
            )
            assert search.calls.count("album-0") == 1
            assert not seeds.rows
        finally:
            h.close()

    asyncio.run(run())
