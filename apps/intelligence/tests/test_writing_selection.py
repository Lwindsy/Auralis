"""Parallel writing, bounded extra evidence and summary-only selection."""

import asyncio
from copy import deepcopy

import pytest

from auralis_intelligence.agents import CallResult, Usage
from auralis_intelligence.album_reviews import WriteStage
from auralis_intelligence.album_reviews.validation import SECTIONS, han_count, validate_long, validate_short
from auralis_intelligence.contracts import ContractError, content_digest, validate_definition
from auralis_intelligence.providers import SearchHit
from auralis_intelligence.providers.testing import ScriptedModelAdapter, StaticSearchAdapter
from auralis_intelligence.ranking import SelectStage
from test_research_screening import environment, ONE, TWO, URLS


def short_output():
    first = "这张专辑把钢琴与轻缓的鼓点放在宽松的空间里，旋律在克制的编配中逐渐展开，演奏细节并未被厚重音墙覆盖[R1]。"
    second = "另一篇评论强调人声与吉他的亲近感，也提到展开速度较慢；若你这次想在深夜安静地听，它的留白值得留意，而不是依靠强烈刺激制造情绪[R2]。"
    return {"text": first + second, "refs": ["R1", "R2"]}


def long_output(extra_ref=None):
    paragraph = "资料描述了演奏之间保留的空间，以及声音在缓慢推进中形成的细微变化。这里的判断来自已读评论，不能把它当作实际试听后的测量结论。不同评论对这种克制是否足够丰富有不同意见，这也提醒我们留意音乐展开的节奏与细节。"
    sections = {name: paragraph * 4 + f"[{'R1' if index % 2 == 0 else 'R2'}]" for index, name in enumerate(SECTIONS)}
    refs = ["R1", "R2"]
    if extra_ref:
        sections["vocals"] += f"补充资料进一步讨论了人声的处理方式[{extra_ref}]。"
        refs.append(extra_ref)
    return {"status": "ok", "sections": sections, "refs": refs, "missing": [], "tool_requests": []}


def needs_more(tool=None, url=None):
    return {"status": "needs_more_research", "sections": {name: "" for name in SECTIONS}, "refs": [], "missing": ["vocals"],
            "tool_requests": [] if tool is None else [{"tool": tool, "section": "vocals", "url": url}]}


async def prepared(**options):
    execution, control, adapter, research, candidate, preferences = await environment(output_limit=4096, **options)
    result = await research.run(execution, candidate, preferences)
    assert result.eligible
    return execution, control, research, result, preferences


def test_short_and_long_dispatch_independently_and_short_does_not_wait_for_long():
    async def run():
        execution, control, research, result, preferences = await prepared()
        started = {name: asyncio.Event() for name in ("short", "long")}
        release = {name: asyncio.Event() for name in started}
        inputs = {}
        class Adapter(ScriptedModelAdapter):
            async def complete(self, binding, request):
                form = request.input["form"]
                inputs[form] = request.input
                started[form].set()
                await release[form].wait()
                return CallResult(short_output() if form == "short" else long_output(), usage=Usage(10, 10, 0))
        research.model.adapter = Adapter([])
        jobs = WriteStage(research.model, research.tools).start(execution, result.dossier, preferences)
        await asyncio.gather(*(event.wait() for event in started.values()))
        assert not jobs.short.done() and not jobs.long.done()
        release["short"].set()
        short = await jobs.short
        assert short.status == "READY" and not jobs.long.done()
        release["long"].set()
        long = await jobs.long
        assert long["status"] == "READY" and long["han_char_count"] >= 1000
        for key in ("sources", "preferences", "request_text", "album"):
            assert inputs["short"][key] == inputs["long"][key]
        assert inputs["short"]["request_text"] == execution.start["input"]["raw"]["request_text"]
        validate_definition("LongReview", long)
        calls = [call for call in execution.calls.values() if call["purpose"] in {"WRITE_SHORT", "WRITE_LONG"}]
        assert len(calls) == 2 and all(call["sent_at"] is not None for call in calls)
        await execution.cleanup()
    asyncio.run(run())


@pytest.mark.parametrize("bad", ["short_length", "short_sentence", "forged_alias", "long_missing", "long_length", "long_citation", "partial_citation"])
def test_invalid_form_or_citation_is_rejected_as_a_whole(bad):
    from test_agent_runtime import BASE
    policy = BASE["agent_policy"]["writing"]
    short, long = short_output(), long_output()
    if bad == "short_length": short["text"] = "太短[R1]。真的[R2]。"
    if bad == "short_sentence": short["text"] = short["text"].replace("。", "，")
    if bad == "forged_alias": short["text"] += "[S1]"
    if bad == "long_missing": long["sections"].pop("vocals")
    if bad == "long_length": long["sections"]["vocals"] = "不足[R1]"
    if bad == "long_citation": long["sections"]["vocals"] += "[R999]"
    if bad == "partial_citation":
        long = needs_more()
        long["refs"] = ["R999"]
    with pytest.raises(ContractError):
        (validate_short if bad.startswith("short") or bad == "forged_alias" else validate_long)(short if bad.startswith("short") or bad == "forged_alias" else long, {"R1", "R2"}, policy)


def test_truncation_and_invalid_output_use_only_shared_repair_allowance():
    async def run():
        execution, control, research, result, preferences = await prepared()
        adapter = ScriptedModelAdapter([CallResult(outcome="COMPLETED", finish="TRUNCATED", usage=Usage(1, 1, 0)), CallResult({"text": "bad", "refs": []}, usage=Usage(1, 1, 0))])
        research.model.adapter = adapter
        short = await WriteStage(research.model, research.tools).short(execution, result.dossier, preferences)
        assert short.status == "FAILED" and short.review is None and len(adapter.requests) == 2
        budget = control.rounds[execution.start["budget"]["round_budget_scope_id"]]
        assert budget.repairs[execution.start["run_id"]] == 1
        await execution.cleanup()
    asyncio.run(run())


def test_long_research_search_then_read_is_bounded_and_records_extra_evidence():
    async def run():
        extra_url = "https://one.example/vocals"
        extra_text = "Artist Album\n" + "The vocals use a close microphone perspective. Quiet breath and restrained phrasing are described alongside the arrangement, with clear discussion of the singer's tone and the room around the voice. " * 3
        execution, control, research, result, preferences = await prepared(pages={URLS[0]: ONE, URLS[1]: TWO, extra_url: extra_text}, writing={"long_tool_turns": 2, "long_extra_max_searches": 1, "long_extra_max_reads": 1})
        research.tools.search_adapter = StaticSearchAdapter((SearchHit(extra_url, "Album vocal review"),))
        adapter = ScriptedModelAdapter([CallResult(needs_more("web_search"), usage=Usage(1, 1, 0)), CallResult(needs_more("web_read", extra_url), usage=Usage(1, 1, 0)), CallResult(long_output("R3"), usage=Usage(1, 1, 0))])
        research.model.adapter = adapter
        output = await WriteStage(research.model, research.tools).long(execution, result.dossier, preferences)
        assert output["status"] == "READY" and len(output["extra_read_ids"]) == 1
        assert output["extra_read_ids"][0] in control.reads
        assert control.reads[output["extra_read_ids"][0]]["receipt"]["purpose"] == "LONG_REVIEW_RESEARCH"
        assert len(adapter.requests) == 3
        assert sum(call["plan"]["purpose"] == "SEARCH" for call in control.invocations.values()) == 1
        await execution.cleanup()
    asyncio.run(run())


@pytest.mark.parametrize("case,expected", [("insufficient", "NEEDS_MORE_RESEARCH"), ("invented_url", "FAILED"), ("budget", "BUDGET_EXHAUSTED"), ("timeout", "TIMED_OUT"), ("cancel", "CANCELLED")])
def test_long_terminal_states_do_not_trigger_automatic_regeneration(case, expected):
    async def run():
        execution, control, research, result, preferences = await prepared()
        started = asyncio.Event()
        class Adapter(ScriptedModelAdapter):
            async def complete(self, binding, request):
                self.requests.append((binding.ref, request))
                started.set()
                if case in {"timeout", "cancel"}: await asyncio.Event().wait()
                return CallResult(needs_more("web_read", "https://one.example/invented") if case == "invented_url" else needs_more(), usage=Usage(1, 1, 0))
        adapter = Adapter([])
        research.model.adapter = adapter
        if case == "budget": control.rounds[execution.start["budget"]["round_budget_scope_id"]].lanes["BACKGROUND"].cap = 0
        if case == "timeout": execution.start["agent_policy"]["timing"]["long_review_timeout_ms"] = 10
        task = asyncio.create_task(WriteStage(research.model, research.tools).long(execution, result.dossier, preferences))
        if case == "cancel":
            await started.wait()
            task.cancel()
        output = await task
        assert output["status"] == expected and output["sections"] is None
        assert len(adapter.requests) == (0 if case == "budget" else 1)
        validate_definition("LongReview", output)
        await execution.cleanup()
    asyncio.run(run())


@pytest.mark.parametrize("mode", ["familiar", "explore", "surprise"])
def test_selection_uses_full_request_mode_and_summaries_without_reading_again(mode):
    async def run():
        execution, control, research, result, preferences = await prepared()
        preferences = deepcopy(preferences)
        preferences["mode"] = mode
        preferences["effective_preference_digest"] = content_digest(preferences, "effective_preference_digest")
        execution.binding["effective_preference_digest"] = preferences["effective_preference_digest"]
        before = len(control.read_claims)
        adapter = ScriptedModelAdapter([CallResult({"order": [{"c": "C1", "verdict": "recommend", "note": "根据本次需求与摘要选择"}]}, usage=Usage(1, 1, 0))])
        research.model.adapter = adapter
        selection = await SelectStage(research.model).run(execution, [result.screening], preferences)
        assert selection.analysis_mode == "PERSONALIZED"
        payload = adapter.requests[0][1].input
        assert payload["mode"] == mode and payload["request_text"] == execution.start["input"]["raw"]["request_text"]
        assert "sources" not in payload and "summary" in payload["candidates"][0]
        assert len(control.read_claims) == before
        await execution.cleanup()
    asyncio.run(run())


def test_shown_candidates_are_excluded_before_selection_and_failure_is_basic():
    async def run():
        execution, control, research, result, preferences = await prepared()
        adapter = ScriptedModelAdapter([CallResult({"order": [{"c": "C999", "verdict": "recommend", "note": "invented"}]}, usage=Usage(1, 1, 0))] * 2)
        research.model.adapter = adapter
        selector = SelectStage(research.model)
        empty = await selector.run(execution, [result.screening], preferences, shown_ids={result.dossier.candidate_id})
        assert empty.selection["status"] == "NOT_RUN" and not adapter.requests
        fallback = await selector.run(execution, [result.screening], preferences)
        assert fallback.analysis_mode == "BASIC" and fallback.selection["status"] == "FAILED"
        assert fallback.screen_order == [result.dossier.candidate_id] and fallback.selection["order"] == []
        assert len(adapter.requests) == 2
        await execution.cleanup()
    asyncio.run(run())


def test_execution_cleanup_cancels_both_owned_writing_jobs():
    async def run():
        execution, control, research, result, preferences = await prepared()
        began = asyncio.Queue()
        class Adapter(ScriptedModelAdapter):
            async def complete(self, binding, request):
                await began.put(request.input["form"])
                await asyncio.Event().wait()
        research.model.adapter = Adapter([])
        jobs = WriteStage(research.model, research.tools).start(execution, result.dossier, preferences)
        assert {await began.get(), await began.get()} == {"short", "long"}
        await execution.cleanup()
        assert jobs.short.done() and jobs.long.done() and not execution.children
        assert (await jobs.short).status == "CANCELLED"
        assert (await jobs.long)["status"] == "CANCELLED"
        assert all(not record.excerpt for record in result.dossier.reads)
    asyncio.run(run())
