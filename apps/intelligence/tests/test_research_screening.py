"""Evidence admission, natural-language scoring context and failure denominators."""

import asyncio

import pytest
from auralis_intelligence.agents import CallResult, Execution, Usage
from auralis_intelligence.album_reviews import ResearchStage
from auralis_intelligence.contracts import ContractError, content_digest
from auralis_intelligence.providers import ModelBinding, ModelGateway, ToolGateway
from auralis_intelligence.providers.testing import ScriptedModelAdapter, StaticSearchAdapter
from auralis_intelligence.providers.web import FetchResponse
from test_agent_runtime import bind, setup, start

ONE = (
    "Artist Album\n"
    + "The piano and drums leave generous space between phrases. The production uses soft room echoes and low dynamic contrast. The critic dislikes the slow development, but describes the quiet atmosphere in precise musical terms. "
    * 3
)
TWO = (
    "Artist Album\n"
    + "Warm guitar harmonics surround a restrained vocal line. The arrangements avoid heavy percussion, placing a sustained organ underneath the melody. Another reviewer values the intimate recording and its understated emotional arc. "
    * 3
)
URLS = ["https://one.example/review", "https://two.example/review"]


class Pages:
    def __init__(self, pages):
        self.pages = pages

    async def fetch(self, url, max_bytes, timeout_seconds):
        value = self.pages[url]
        if isinstance(value, Exception):
            raise value
        if callable(value):
            return await value()
        return FetchResponse(200, {"content-type": "text/plain"}, value.encode("utf-8"))


def good_reply(binding, request, score=4, bad_read=None):
    sources = request.input["sources"]
    if request.schema_name == "research_v2" and not sources:
        return CallResult(
            {
                "action": "read",
                "query": "",
                "urls": [x["url"] for x in request.input["available_sources"]],
                "reason": "Relevant sources",
                "assessment": None,
            },
            usage=Usage(20, 20, 0),
        )
    reads = [
        {
            "ref": source["ref"],
            "about_target": "yes",
            "substantive": "no" if source["ref"] == bad_read else "yes",
        }
        for source in sources
    ]
    refs = [item["ref"] for item in reads if item["substantive"] == "yes"]
    pref = request.input["preferences"][0]["ref"]
    output = {
        "reads": reads,
        "score": score,
        "matches": [{"pref": pref, "refs": refs, "note": "克制的编配与深夜独处需求有关"}]
        if refs
        else [],
        "tensions": [],
        "unknowns": ["曲目间动态变化"],
        "summary": "材料讨论克制编配和空间感，对展开速度有分歧。",
    }
    if request.schema_name == "research_v2":
        output = {
            "action": "finish",
            "query": "",
            "urls": [],
            "reason": "Evidence sufficient",
            "assessment": output,
        }
    return CallResult(output, usage=Usage(20, 20, 0))


async def environment(
    pages=None, reply=good_reply, *, urls=None, threshold=3, output_limit=1000, writing=None
):
    request = start()
    request["capabilities"] = ["web_search.v1", "web_read.v1"]
    request["web_guards"].update(max_bytes=10000, read_timeout_ms=1000, max_redirects=2)
    request["agent_policy"]["screening"].update(
        shortlist_min_score=threshold, min_review_chars=200, excerpt_token_cap=2000
    )
    request["agent_policy"]["writing"].update(writing or {})
    request["agent_policy"]["digest"] = content_digest(request["agent_policy"], "digest")
    for binding in request["model_bindings"].values():
        binding.update(max_input_tokens=20000, max_output_tokens=output_limit)
    request["payload_digest"] = content_digest(request)
    _, control = setup(request)
    urls = URLS if urls is None else urls
    control.known_review_refs["album-1"] = [
        {"read_id": f"locator-{index}", "url": url, "publisher_key": url.split("/")[2]}
        for index, url in enumerate(urls)
    ]
    execution = Execution(request, control)
    bundle = await bind(execution, control)
    preferences = control.executions[execution.id]["effective_preferences"]
    adapter = ScriptedModelAdapter(reply)
    bindings = {
        value["ref"]: ModelBinding(**value, web_search=True)
        for value in request["model_bindings"].values()
    }
    model = ModelGateway(adapter, bindings)
    tools = ToolGateway(
        Pages(pages or dict(zip(URLS, (ONE, TWO)))),
        search_adapter=StaticSearchAdapter(()),
        model_gateway=model,
    )
    stage = ResearchStage(model, tools, genre_names={"jazz": "爵士"})
    return execution, control, adapter, stage, bundle["accepted"][0], preferences


@pytest.mark.parametrize("count", [1, 2])
def test_actual_evidence_not_source_count(count):
    async def run():
        execution, control, adapter, stage, candidate, prefs = await environment(urls=URLS[:count])
        result = await stage.run(execution, candidate, prefs)
        assert result.eligible
        assert len(result.screening["qualified_read_ids"]) == count
        assert adapter.requests[0][1].input["request_text"]
        assert adapter.requests[0][1].input["available_sources"]
        assert all(r.excerpt and not r.result.text for r in result.dossier.qualified)
        await execution.cleanup()
        assert all(not r.excerpt for r in result.dossier.reads)

    asyncio.run(run())


@pytest.mark.parametrize("score,expected", [(1, False), (2, False), (3, True), (5, True)])
def test_grounded_score_threshold(score, expected):
    async def run():
        execution, _, _, stage, candidate, prefs = await environment(
            reply=lambda b, r: good_reply(b, r, score)
        )
        result = await stage.run(execution, candidate, prefs)
        assert result.eligible == expected
        await execution.cleanup()

    asyncio.run(run())


def test_model_rejects_irrelevant_page_without_mechanical_title_gate():
    async def run():
        execution, _, _, stage, candidate, prefs = await environment(
            urls=URLS[:1], reply=lambda b, r: good_reply(b, r, bad_read="R1")
        )
        result = await stage.run(execution, candidate, prefs)
        assert not result.eligible and not result.screening["qualified_read_ids"]
        await execution.cleanup()

    asyncio.run(run())


def test_unseen_url_is_never_fetched():
    def reply(b, r):
        return CallResult(
            {
                "action": "read",
                "urls": ["https://unseen.example/x"],
                "query": "",
                "reason": "bad",
                "assessment": None,
            },
            usage=Usage(1, 1, 0),
        )

    async def run():
        execution, control, _, stage, candidate, prefs = await environment(reply=reply)
        result = await stage.run(execution, candidate, prefs)
        assert not result.eligible and not control.reads
        await execution.cleanup()

    asyncio.run(run())


def test_unknown_candidate_rejected():
    async def run():
        execution, _, _, stage, candidate, prefs = await environment()
        candidate["release_group_id"] = "unknown"
        with pytest.raises(ContractError):
            await stage.run(execution, candidate, prefs)
        await execution.cleanup()

    asyncio.run(run())
