"""Fake-HTTP adapter evidence; never contacts real model or review services."""

import asyncio
import json
import socket

import httpx
import pytest
from auralis_intelligence.agents import CallDenied, Execution
from auralis_intelligence.contracts import content_digest, validate_definition
from auralis_intelligence.providers import (
    ModelBinding,
    ModelGateway,
    ModelRequest,
    OpenAIResponsesAdapter,
    ToolGateway,
)
from auralis_intelligence.providers.web import (
    FetchFailure,
    FetchResponse,
    PublicResolver,
    checked_url,
)
from test_agent_runtime import setup, start

OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {"score": {"type": "integer", "minimum": 1, "maximum": 5}},
    "required": ["score"],
    "additionalProperties": False,
}
BINDING = ModelBinding(
    "screen", "r1", "synthetic-model", "synthetic-price", 8000, 100, web_search=True
)
REQUEST = ModelRequest(
    "Test policy", {"request_text": "想听安静的"}, "screen_v1", OUTPUT_SCHEMA, 100
)


def response(status="completed", text='{"score": 4}', *, refusal=False, usage=True):
    return {
        "id": "response-1",
        "status": status,
        "output": [
            {
                "type": "message",
                "content": [
                    {"type": "refusal", "refusal": "no"}
                    if refusal
                    else {"type": "output_text", "text": text}
                ],
            }
        ],
        "usage": {
            "input_tokens": 4,
            "output_tokens": 6,
            "input_tokens_details": {"cached_tokens": 2},
        }
        if usage
        else None,
    }


@pytest.mark.parametrize(
    "raw,finish",
    [
        (response(), "COMPLETE"),
        (response("incomplete"), "TRUNCATED"),
        (response(refusal=True), "REFUSED"),
        (response(text='{"score": 9}'), "ERROR"),
        (response(text="not-json"), "ERROR"),
    ],
)
def test_model_normal_structured_truncated_refused_and_invalid(raw, finish):
    async def run():
        seen = []

        async def handler(request):
            seen.append(json.loads(request.content))
            return httpx.Response(200, json=raw)

        async with OpenAIResponsesAdapter(
            "synthetic-key", transport=httpx.MockTransport(handler), test_transport=True
        ) as adapter:
            result = await adapter.complete(BINDING, REQUEST)
        assert result.finish == finish and result.outcome == "COMPLETED"
        assert result.usage.cached_input_tokens == 2
        assert result.output == (
            {"score": 4}
            if finish == "COMPLETE"
            else {"score": 9}
            if raw == response(text='{"score": 9}')
            else None
        )
        assert len(seen) == 1 and seen[0]["store"] is False and seen[0]["stream"] is False
        assert seen[0]["text"]["format"]["strict"] is True

    asyncio.run(run())


@pytest.mark.parametrize(
    "status,outcome", [(429, "NOT_PROCESSED"), (401, "NOT_PROCESSED"), (500, "UNCERTAIN")]
)
def test_http_errors_are_single_attempt_and_never_expose_error_body(status, outcome):
    async def run():
        calls = []

        async def handler(request):
            calls.append(request)
            return httpx.Response(
                status, text="private error details", headers={"x-request-id": "id-1"}
            )

        async with OpenAIResponsesAdapter(
            "synthetic-key", transport=httpx.MockTransport(handler), test_transport=True
        ) as adapter:
            result = await adapter.complete(BINDING, REQUEST)
        assert len(calls) == 1 and result.outcome == outcome and result.output is None
        assert result.usage.input_tokens is None and "private" not in repr(result)

    asyncio.run(run())


@pytest.mark.parametrize(
    "error,outcome",
    [
        (httpx.ConnectError("secret-url"), "NOT_PROCESSED"),
        (httpx.ReadTimeout("secret-url"), "UNCERTAIN"),
    ],
)
def test_connect_failure_and_sent_read_timeout(error, outcome):
    async def run():
        async def handler(request):
            raise error

        async with OpenAIResponsesAdapter(
            "synthetic-key", transport=httpx.MockTransport(handler), test_transport=True
        ) as adapter:
            result = await adapter.complete(BINDING, REQUEST)
        assert result.outcome == outcome and "secret" not in repr(result)

    asyncio.run(run())


class BrokenStream(httpx.AsyncByteStream):
    def __init__(self, wait=False):
        self.wait = wait
        self.started = asyncio.Event()
        self.closed = False

    async def __aiter__(self):
        yield b'{"status":'
        self.started.set()
        if self.wait:
            await asyncio.Event().wait()
        raise httpx.ReadError("private upstream detail")

    async def aclose(self):
        self.closed = True


@pytest.mark.parametrize("cancel", [False, True])
def test_disconnect_and_cancel_close_inflight_http_stream(cancel):
    async def run():
        stream = BrokenStream(cancel)

        async def handler(request):
            return httpx.Response(200, stream=stream)

        async with OpenAIResponsesAdapter(
            "synthetic-key", transport=httpx.MockTransport(handler), test_transport=True
        ) as adapter:
            task = asyncio.create_task(adapter.complete(BINDING, REQUEST))
            await stream.started.wait()
            if cancel:
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task
            else:
                assert (await task).outcome == "UNCERTAIN"
        assert stream.closed

    asyncio.run(run())


def test_live_configuration_is_not_implicitly_approved():
    async def run():
        called = False

        async def handler(request):
            nonlocal called
            called = True
            return httpx.Response(200, json=response())

        async with OpenAIResponsesAdapter(
            "synthetic-key", transport=httpx.MockTransport(handler)
        ) as adapter:
            with pytest.raises(CallDenied, match="LIVE_CONFIGURATION_NOT_APPROVED"):
                await adapter.complete(BINDING, REQUEST)
        assert not called

    asyncio.run(run())


def test_unknown_usage_is_null_and_search_locations_are_not_read_receipts():
    async def run():
        raw = response(usage=False)
        raw["output"] = [
            {
                "type": "web_search_call",
                "action": {
                    "type": "search",
                    "sources": [{"url": "https://one.example/review", "title": "Album review"}],
                },
            }
        ]

        async def handler(request):
            data = json.loads(request.content)
            assert data["max_tool_calls"] == 1
            assert data["tools"][0]["filters"]["allowed_domains"] == ["one.example"]
            assert "blocked_domains" not in data["tools"][0]["filters"]
            return httpx.Response(200, json=raw)

        async with OpenAIResponsesAdapter(
            "synthetic-key", transport=httpx.MockTransport(handler), test_transport=True
        ) as adapter:
            result = await adapter.search(BINDING, "Artist Album review", ("one.example",), ())
        assert result.usage.input_tokens is None and result.usage.output_tokens is None
        assert result.output[0].url == "https://one.example/review"
        assert not hasattr(result.output[0], "receipt")

    asyncio.run(run())


def web_execution():
    request = start()
    request["capabilities"] = ["web_read.v1", "web_search.v1"]
    request["source_policy"]["guards"].update(
        max_bytes=10000, read_timeout_ms=1000, max_redirects=2
    )
    request["model_bindings"]["screen"] = BINDING.wire()
    request["payload_digest"] = content_digest(request)
    _, control = setup(request)
    return Execution(request, control), control


def test_gateway_uses_bound_model_authorization_and_retains_invocation_id():
    async def run():
        execution, control = web_execution()

        async def handler(request):
            return httpx.Response(200, json=response())

        async with OpenAIResponsesAdapter(
            "synthetic-key", transport=httpx.MockTransport(handler), test_transport=True
        ) as adapter:
            gateway = ModelGateway(adapter, {BINDING.ref: BINDING})
            result = await gateway.generate(
                execution,
                role="screen",
                purpose="SCREEN",
                system=REQUEST.system,
                input=REQUEST.input,
                output_schema=OUTPUT_SCHEMA,
                prompt_version="screen.v1",
            )
        assert result.invocation_id in control.invocations
        assert control.invocations[result.invocation_id]["settled"]
        await execution.cleanup()

    asyncio.run(run())


class FakeFetcher:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.urls = []

    async def fetch(self, url, max_bytes, timeout_seconds):
        self.urls.append(url)
        return next(self.responses)


HTML = b"<html><nav>ignore navigation</nav><article><h1>Artist Album</h1><p>Detailed music analysis.</p><script>ignore this</script></article></html>"


def test_actual_read_extracts_text_and_registers_receipt_after_redirect():
    async def run():
        execution, control = web_execution()
        fetcher = FakeFetcher(
            [
                FetchResponse(302, {"location": "https://two.example/final"}, b""),
                FetchResponse(200, {"content-type": "text/html"}, HTML),
            ]
        )
        result = await ToolGateway(fetcher).read(
            execution,
            candidate_id="album-1",
            url="https://one.example/start",
            title="Album",
            artist="Artist",
        )
        assert (
            result.receipt["status"] == "READ_OK"
            and result.receipt["publisher_key"] == "two.example"
        )
        assert result.receipt["read_id"] in control.reads
        assert result.receipt["checks"] == {"mentions_title": True, "mentions_artist": True}
        assert "Detailed music" in result.text and "ignore" not in result.text
        assert sum(operation == "read-authorization" for _, operation in control.events) == 2
        validate_definition("ReadReceipt", result.receipt)
        result.clear()
        assert result.text == ""
        await execution.cleanup()

    asyncio.run(run())


@pytest.mark.parametrize(
    "url",
    [
        "http://one.example/a",
        "https://127.0.0.1/a",
        "https://[::1]/a",
        "https://2130706433/a",
        "https://0x7f000001/a",
        "https://u:p@one.example/a",
        "https://one.example:22/a",
        "file:///etc/passwd",
    ],
)
def test_url_policy_rejects_non_web_private_literals_and_credentials(url):
    with pytest.raises(FetchFailure):
        checked_url(url)


def test_resolver_validates_addresses_used_for_connection_not_a_preflight_lookup():
    class Resolver:
        def __init__(self, address):
            self.address = address

        async def resolve(self, host, port, family):
            return [
                {
                    "hostname": host,
                    "host": self.address,
                    "port": port,
                    "family": socket.AF_INET,
                    "proto": 0,
                    "flags": 0,
                }
            ]

        async def close(self):
            pass

    async def run():
        with pytest.raises(FetchFailure, match="PRIVATE_NETWORK_BLOCKED"):
            await PublicResolver(Resolver("169.254.169.254")).resolve("one.example", 443)
        result = await PublicResolver(Resolver("8.8.8.8")).resolve("one.example", 443)
        assert result[0]["host"] == "8.8.8.8"

    asyncio.run(run())


@pytest.mark.parametrize(
    "kind",
    [
        "private_redirect",
        "oversize",
        "no_permission",
        "no_locator_permission",
        "expired",
        "revoked_during_read",
    ],
)
def test_web_guards_prevent_unapproved_content_use(kind):
    async def run():
        execution, control = web_execution()
        replies = [FetchResponse(200, {"content-type": "text/html"}, HTML)]
        if kind == "private_redirect":
            replies[0] = FetchResponse(302, {"location": "https://127.0.0.1/private"}, b"")
        if kind == "oversize":
            replies[0] = FetchResponse(200, {"content-type": "text/plain"}, b"x" * 10001)
        if kind == "no_permission":
            control.policy["rules"][0]["decision"] = "DENY"
        if kind == "no_locator_permission":
            control.policy["rules"][0]["uses"].remove("STORE_LOCATOR")
        if kind == "expired":
            control.policy["valid_until"] = "2000-01-01T00:00:00Z"
        fetcher = FakeFetcher(replies)
        if kind == "revoked_during_read":
            original = fetcher.fetch

            async def revoke(*args):
                control.policy["rules"][0]["decision"] = "DENY"
                return await original(*args)

            fetcher.fetch = revoke
        tool = ToolGateway(fetcher)
        if kind == "expired":
            with pytest.raises(CallDenied, match="SOURCE_POLICY_EXPIRED"):
                await tool.read(
                    execution,
                    candidate_id="album-1",
                    url="https://one.example/a",
                    title="Album",
                    artist="Artist",
                )
        else:
            result = await tool.read(
                execution,
                candidate_id="album-1",
                url="https://one.example/a",
                title="Album",
                artist="Artist",
            )
            assert not result.text and result.receipt["status"] != "READ_OK"
        assert not control.reads
        if kind in {"no_permission", "expired"}:
            assert not fetcher.urls
        await execution.cleanup()

    asyncio.run(run())


@pytest.mark.parametrize("cancel", [False, True])
def test_web_timeout_and_cancel_release_resources(cancel):
    async def run():
        execution, control = web_execution()
        control.policy["guards"]["read_timeout_ms"] = 1000 if cancel else 10
        began, closed = asyncio.Event(), asyncio.Event()

        class Fetcher:
            async def fetch(self, *args):
                began.set()
                try:
                    await asyncio.Event().wait()
                finally:
                    closed.set()

        task = asyncio.create_task(
            ToolGateway(Fetcher()).read(
                execution,
                candidate_id="album-1",
                url="https://one.example/a",
                title="Album",
                artist="Artist",
            )
        )
        await began.wait()
        if cancel:
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        else:
            assert (await task).receipt["failure_reason"] == "READ_TIMEOUT"
        assert closed.is_set() and not execution.scheduler.running
        await execution.cleanup()

    asyncio.run(run())


def test_declared_maximum_execution_id_survives_call_and_receipt_suffixes():
    async def run():
        request = start("e" * 160)
        _, control = setup(request)
        execution = Execution(request, control)
        from auralis_intelligence.agents import CallResult, Usage

        async def send():
            return CallResult({"score": 4}, usage=Usage(1, 1, 0))

        result = await execution.invoke(
            execution.invocation_plan("SCREEN", "screen", {}), send, timeout_seconds=1
        )
        assert len(result.invocation_id) > 160
        assert control.invocations[result.invocation_id]["settled"]
        await execution.cleanup()

    asyncio.run(run())


def test_model_timeout_on_supported_python_310_is_uncertain_and_closed():
    async def run():
        request = start()
        _, control = setup(request)
        execution = Execution(request, control)
        closed = asyncio.Event()

        async def send():
            try:
                await asyncio.Event().wait()
            finally:
                closed.set()

        result = await execution.invoke(
            execution.invocation_plan("SCREEN", "screen", {}), send, timeout_seconds=0.01
        )
        assert result.outcome == "UNCERTAIN" and closed.is_set()
        assert control.invocations[result.invocation_id]["uncertain"]
        await execution.cleanup()

    asyncio.run(run())


def test_search_policy_change_between_snapshot_and_grant_prevents_dispatch():
    from auralis_intelligence.providers.testing import ScriptedModelAdapter, StaticSearchAdapter

    async def run():
        execution, control = web_execution()

        class Search(StaticSearchAdapter):
            calls = 0

            async def search(self, *args):
                self.calls += 1
                return await super().search(*args)

        search = Search(())
        model = ModelGateway(ScriptedModelAdapter([]), {BINDING.ref: BINDING})
        original = control.request

        async def changed(identifier, operation, payload):
            if operation == "invocation-grants":
                control.policy["policy_epoch"] += 1
            return await original(identifier, operation, payload)

        control.request = changed
        with pytest.raises(CallDenied, match="SOURCE_POLICY_CHANGED"):
            await ToolGateway(search_adapter=search, model_gateway=model).search(
                execution, candidate_id="album-1", query="Artist Album review"
            )
        assert search.calls == 0 and not control.invocations
        await execution.cleanup()

    asyncio.run(run())
