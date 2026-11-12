"""Complete independent first-batch runs plus actual loopback HTTP transport."""

import asyncio
import json
import subprocess
import sys
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
import pytest
from aiohttp import web
from auralis_intelligence.agents import CallResult, Execution, Usage
from auralis_intelligence.agents.demo import DemoHarness, _reply
from auralis_intelligence.agents.http import (
    ControlHTTPClient,
    create_control_double_app,
    create_execution_app,
)
from auralis_intelligence.contracts import content_digest, digest, envelope, validate_message
from auralis_intelligence.providers.web import FetchResponse


@pytest.mark.parametrize("scenario", ["album", "genre", "text", "mixed"])
def test_each_positive_input_runs_default_50_15_3_without_real_providers(scenario, tmp_path):
    async def run():
        harness = DemoHarness(scenario=scenario, root=tmp_path)
        try:
            summary = await harness.run()
            assert summary["execution_status"] == "CLOSED", summary
            assert (
                summary["first_outcome"] == "DISPLAYABLE"
                and summary["analysis_mode"] == "PERSONALIZED"
            )
            assert (
                summary["first_card_count"] == 15
                and summary["published_count"] == 3
                and summary["long_reviews"] == 15
            )
            interpret = [
                request
                for _, request in harness.adapter.requests
                if request.schema_name.startswith("genre_pass")
            ]
            assert len(interpret) == (2 if scenario in {"text", "mixed"} else 0)
            assert summary["timing"]["shortlist_actual_count"] == 15 and summary["slot_released"]
        finally:
            harness.close()
        assert not harness.snapshot.exists()

    asyncio.run(run())


def test_nondefault_counts_use_same_bound_configuration(tmp_path):
    async def run():
        harness = DemoHarness(r=5, k=4, n=2, root=tmp_path)
        try:
            summary = await harness.run()
            assert summary["first_card_count"] == 4 and summary["published_count"] == 2
            assert all(
                delivery["binding"]["selection_config_revision"] == "synthetic-5-4-2"
                for delivery in harness.control.deliveries.values()
            )
        finally:
            harness.close()

    asyncio.run(run())


@pytest.mark.parametrize(
    "case,expected",
    [
        ("interpret", "FAILED"),
        ("reads", "NO_MATCH"),
        ("no_coverage", "NO_MATCH"),
        ("writing", "DISPLAYABLE"),
        ("select", "DISPLAYABLE"),
    ],
)
def test_failures_partial_and_degraded_are_distinguishable(case, expected, tmp_path):
    async def run():
        harness = DemoHarness(r=3, k=3, n=3, root=tmp_path)
        try:
            if case == "reads":
                original = harness.tools.fetcher.fetch

                async def one_only(url, *args):
                    if "two.example" in url:
                        return FetchResponse(
                            200, {"content-type": "text/plain"}, b"This is a different work."
                        )
                    return await original(url, *args)

                harness.tools.fetcher.fetch = one_only
            if case == "no_coverage":
                harness.request["input"]["exclusions"] = [
                    {"release_group_id": identifier, "reason": "HEARD"}
                    for identifier in harness.control.catalog
                ]
                harness.request["input"]["exclusion_digest"] = digest(
                    harness.request["input"]["exclusions"]
                )
                harness.request["payload_digest"] = content_digest(harness.request)
            first_short = []

            def replies(binding, request):
                if (
                    case == "reads"
                    and request.schema_name == "research_v2"
                    and request.input["sources"]
                ):
                    return CallResult(
                        {
                            "action": "abandon",
                            "query": "",
                            "urls": [],
                            "assessment": None,
                            "reason": "Actual page is insufficient",
                        },
                        usage=Usage(1, 1, 0),
                    )
                fail = (
                    case == "interpret"
                    and request.schema_name.startswith("genre_pass")
                    or case == "select"
                    and request.schema_name == "select_v1"
                )
                if (
                    case == "writing"
                    and request.schema_name.startswith("short_review_v1")
                    and not first_short
                ):
                    first_short.append(True)
                    fail = True
                return (
                    CallResult(outcome="COMPLETED", finish="REFUSED", usage=Usage(1, 1, 0))
                    if fail
                    else _reply(binding, request)
                )

            harness.adapter.replies = replies
            summary = await harness.run()
            assert summary["first_outcome"] == expected, summary
            if case == "writing":
                assert summary["published_count"] == 2
            if case == "select":
                assert summary["analysis_mode"] == "BASIC"
            if case == "reads":
                assert summary["reason_code"] == "REVIEW_COVERAGE_INSUFFICIENT"
            if case == "no_coverage":
                assert summary["reason_code"] == "RECALL_NO_COVERAGE"
            if case == "interpret":
                first = next(
                    item
                    for item in harness.control.deliveries.values()
                    if item["kind"] == "CARD_BATCH"
                )
                assert first["binding"] is None and first["payload"]["timing"] is None
        finally:
            harness.close()

    asyncio.run(run())


def test_no_positive_signal_hands_back_to_java_without_fake_recall(tmp_path):
    async def run():
        harness = DemoHarness(scenario="no-positive", root=tmp_path)
        try:
            result = await harness.run()
            assert result["handoff"] == "JAVA_CURATED" and result["model_calls"] == 0
            assert result["first_outcome"] is None and not harness.control.deliveries
            assert not any(
                operation == "candidate-validation" for _, operation in harness.control.events
            )
        finally:
            harness.close()

    asyncio.run(run())


def test_lost_delivery_response_uses_original_id_and_control_failure_sends_nothing(tmp_path):
    async def run():
        harness = DemoHarness(r=3, k=2, n=1, root=tmp_path)
        try:
            harness.control.lose_response_once.add("delivery")
            result = await harness.run()
            assert result["published_count"] == 1
            attempts = sum(operation == "delivery" for _, operation in harness.control.events)
            assert attempts == len(harness.control.deliveries) + 1
        finally:
            harness.close()
        harness = DemoHarness(root=tmp_path)
        try:
            harness.control.unavailable.add("invocation-grants")
            result = await harness.run()
            assert result["first_outcome"] == "FAILED" and not harness.adapter.requests
        finally:
            harness.close()

    asyncio.run(run())


def test_over_eight_seconds_is_recorded_without_removing_first_batch(tmp_path):
    async def run():
        harness = DemoHarness(r=3, k=3, n=3, root=tmp_path)
        try:
            now = [0.0]

            def replies(binding, request):
                if request.schema_name.startswith("short_review_v1"):
                    now[0] = 9.0
                return _reply(binding, request)

            harness.adapter.replies = replies
            harness.control.register(harness.request)
            execution = Execution(harness.request, harness.control, monotonic=lambda: now[0])
            await harness.pipeline(harness.request)(execution)
            first = next(
                item["payload"]
                for item in harness.control.deliveries.values()
                if item["kind"] == "CARD_BATCH"
            )
            assert first["timing"]["agent_wait_ms"] == 9000
            assert len(first["cards"]) == 3 and first["outcome"] == "DISPLAYABLE"
            await execution.cleanup()
        finally:
            harness.close()

    asyncio.run(run())


@asynccontextmanager
async def running(app):
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    try:
        yield f"http://127.0.0.1:{runner.addresses[0][1]}"
    finally:
        await runner.cleanup()


def test_actual_http_execution_and_control_client_use_same_contract(tmp_path):
    async def run():
        harness = DemoHarness(r=3, k=2, n=1, root=tmp_path)
        try:
            async with running(
                create_control_double_app(harness.control, token="control-test")
            ) as control_url:
                client = ControlHTTPClient(control_url, "control-test")
                try:
                    app = create_execution_app(
                        harness.supervisor,
                        client,
                        harness.pipeline,
                        token="service-test",
                        prepare=harness.control.register,
                    )
                    async with (
                        running(app) as url,
                        httpx.AsyncClient(base_url=url, trust_env=False) as http,
                    ):
                        assert (await http.get("/internal/v1/runtime")).status_code == 401
                        http.headers.update(
                            {"Authorization": "Bearer service-test", "X-Auralis-Audience": "wrong"}
                        )
                        assert (await http.get("/internal/v1/runtime")).status_code == 403
                        http.headers["X-Auralis-Audience"] = "intelligence"
                        runtime = (await http.get("/internal/v1/runtime")).json()
                        validate_message(runtime)
                        path = "/internal/v1/executions/" + harness.request["execution_id"]
                        sent = envelope("RoundStart", harness.request, "request-http")
                        response = await http.put(path, json=sent)
                        assert response.status_code == 201, response.text
                        validate_message(response.json())
                        assert (await http.put(path, json=sent)).status_code == 200
                        execution = await harness.supervisor.wait(harness.request["execution_id"])
                        view = (await http.get(path)).json()
                        validate_message(view)
                        assert view["payload"]["slot_released"] and execution.status == "CLOSED", (
                            execution.error_code
                        )
                        assert harness.summary(execution)["published_count"] == 1
                finally:
                    await client.close()
        finally:
            harness.close()

    asyncio.run(run())


def test_cli_demo_is_a_real_separate_process(tmp_path):
    source = Path(__file__).parents[1] / "src"
    import os

    env = {**os.environ, "PYTHONPATH": str(source)}
    process = subprocess.run(
        [
            sys.executable,
            "-m",
            "auralis_intelligence.agents",
            "demo",
            "--scenario",
            "genre",
            "--r",
            "3",
            "--k",
            "2",
            "--n",
            "1",
            "--include-calls",
        ],
        cwd=tmp_path,
        env=env,
        capture_output=True,
        encoding="utf-8",
        timeout=30,
    )
    assert process.returncode == 0, process.stderr
    result = json.loads(process.stdout)
    assert result["synthetic"] and not result["paid_requests"] and result["published_count"] == 1
    assert len(result["calls"]) == result["model_calls"]
    assert "request_text" not in json.dumps(result["calls"])


def test_first_batch_is_delivered_while_long_requests_are_still_running(tmp_path):
    async def run():
        harness = DemoHarness(r=3, k=2, n=1, root=tmp_path)
        try:
            release, first_seen = asyncio.Event(), asyncio.Event()
            original_model = harness.adapter.complete

            async def delayed(binding, request):
                if request.schema_name.startswith("long_review_v1"):
                    await release.wait()
                return await original_model(binding, request)

            harness.adapter.complete = delayed
            original_control = harness.control.request

            async def observe(identifier, operation, payload):
                result = await original_control(identifier, operation, payload)
                if operation == "delivery" and payload["kind"] == "CARD_BATCH":
                    first_seen.set()
                return result

            harness.control.request = observe
            harness.control.register(harness.request)
            _, execution = await harness.supervisor.accept(
                harness.request, harness.pipeline(harness.request), harness.control
            )
            await asyncio.wait_for(first_seen.wait(), 5)
            assert not execution.slot_released
            assert not any(
                item["kind"] == "LONG_REVIEW" for item in harness.control.deliveries.values()
            )
            release.set()
            await harness.supervisor.wait(execution.id)
            assert harness.summary(execution)["long_reviews"] == 2
        finally:
            harness.close()

    asyncio.run(run())


def test_unsupported_constraint_retains_precise_unicode_span(tmp_path):
    async def run():
        harness = DemoHarness(scenario="text", root=tmp_path)
        try:
            raw = harness.request["input"]["raw"]
            raw["request_text"] = "今晚想放松，只听爵士或者摇滚。"
            raw["input_digest"] = content_digest(raw, "input_digest")
            harness.request["payload_digest"] = content_digest(harness.request)
            quote = "只听爵士或者摇滚"

            def reply(binding, request):
                if request.schema_name.startswith("genre_pass2"):
                    return CallResult(
                        {
                            "preferences": [],
                            "constraints": [],
                            "named_genres": [],
                            "unresolved_constraints": [quote],
                            "soft_preferences": [],
                        },
                        usage=Usage(1, 1, 0),
                    )
                return _reply(binding, request)

            harness.adapter.replies = reply
            summary = await harness.run()
            first = next(
                item["payload"]
                for item in harness.control.deliveries.values()
                if item["kind"] == "CARD_BATCH"
            )
            assert summary["first_outcome"] == "NEEDS_INPUT"
            start = raw["request_text"].index(quote)
            assert first["input_issues"][0]["text_span"] == {
                "start": start,
                "end": start + len(quote),
            }
        finally:
            harness.close()

    asyncio.run(run())


def test_wrong_candidate_binding_is_rejected_before_research(tmp_path):
    async def run():
        harness = DemoHarness(scenario="genre", root=tmp_path)
        try:
            original = harness.control.request

            async def wrong(identifier, operation, payload):
                result = await original(identifier, operation, payload)
                if operation == "candidate-validation":
                    result["binding"]["selection_config_revision"] = "wrong"
                return result

            harness.control.request = wrong
            summary = await harness.run()
            assert (
                summary["first_outcome"] == "FAILED"
                and summary["reason_code"] == "BINDING_MISMATCH"
            )
            assert not harness.adapter.requests and not harness.control.reads
        finally:
            harness.close()

    asyncio.run(run())
