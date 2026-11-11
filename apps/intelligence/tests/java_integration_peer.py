"""Test-only provider adapters around the unchanged production serve composition.

The Java control client, HTTP resource, supervisor, recall, evidence, budgets and
publication pipeline are real. Only model/search answers and page bytes are synthetic.
"""

import argparse
import asyncio
import json
import os
import re
import time
import traceback
from pathlib import Path

from aiohttp import web
from auralis_intelligence.agents import CallResult, Usage
from auralis_intelligence.agents import __main__ as service
from auralis_intelligence.agents.ports import ControlUnavailable
from auralis_intelligence.providers import ToolGateway
from auralis_intelligence.providers.types import SearchHit
from auralis_intelligence.providers.web import FetchResponse


class Fixtures:
    def __init__(self, root):
        self.root = root
        self.repairs = set()
        self.dropped = set()
        self.tasks = set()

    def controls(self):
        return json.loads((self.root / "faults.json").read_text(encoding="utf-8"))

    def event(self, kind, **values):
        with (self.root / "provider-events.jsonl").open("a", encoding="utf-8") as output:
            output.write(json.dumps({"kind": kind, "monotonic": time.monotonic(), **values}) + "\n")

    def check_binding(self, binding, *, search=False):
        pass

    async def complete(self, binding, request):
        self.event("model", schema=request.schema_name)
        await asyncio.sleep(self.controls().get("model_delay_seconds", 0.01))
        faults = self.controls()
        while faults.get("hold_models") or (
            faults.get("hold_long") and request.schema_name.startswith("long_review")
        ):
            await asyncio.sleep(0.05)
            faults = self.controls()
        if faults.get("provider_429"):
            self.event("provider_429", schema=request.schema_name)
            return CallResult(outcome="NOT_PROCESSED", finish="ERROR", provider_http_status=429)
        payload = request.input
        english = "_en" in request.schema_name
        jazz = "Jazz" if english else "爵士"
        gentle = "Gentle" if english else "轻柔"
        if request.schema_name.startswith("genre_pass1"):
            return CallResult(
                {"branches": [{"id": "branch-jazz", "quote": jazz}]}, usage=Usage(100, 100, 0)
            )
        if request.schema_name.startswith("genre_pass2"):
            return CallResult(
                {
                    "preferences": [
                        {
                            "id": "00000000-0000-0000-0000-000000000065",
                            "kind": "prefer",
                            "basis": "explicit",
                            "quote": jazz,
                        }
                    ],
                    "constraints": [],
                    "unresolved_constraints": [],
                    "soft_preferences": [
                        {
                            "description": gentle,
                            "kind": "context",
                            "basis": "explicit",
                            "quote": gentle,
                        }
                    ],
                },
                usage=Usage(100, 100, 0),
            )
        if request.schema_name.startswith("batch_v6"):
            candidates = payload["candidates"]
            if faults.get("invalid_once") and "batch" not in self.repairs:
                self.repairs.add("batch")
                return CallResult({}, usage=Usage(10, 10, 0))
            if faults.get("unknown_usage"):
                return CallResult(outcome="UNCERTAIN", finish="ERROR")
            text = "这张合成测试专辑以舒缓钢琴和轻柔鼓点为主，演奏之间保留清晰的空间，旋律自然展开，适合想安静聆听的时刻，具体音色描述来自本次读取的测试资料[R1]。"
            if english:
                text = (
                    "Soft piano and gentle drums leave space between phrases, so the melody can develop at an unhurried pace [R1]. "
                    "The recording keeps the instruments distinct even when the percussion grows busier near the end of a passage."
                )
            return CallResult(
                {
                    "assessments": [
                        {"c": c["c"], "reason": "SUFFICIENT", "refs": ["R1"]} for c in candidates
                    ],
                    "cards": [
                        {"c": c["c"], "score": 4, "text": text, "refs": ["R1"]}
                        for c in candidates[: payload["target_count"]]
                    ],
                },
                usage=Usage(100, 100, 0),
            )
        if request.schema_name.startswith("long_review"):
            return CallResult(
                {
                    "status": "ok",
                    "sections": {
                        "production": "The recording leaves space between the instruments [R1]."
                        if english
                        else "合成资料描述清晰而克制的录音，乐器之间留有足够空间[R1]。",
                        "instrumentation": "Piano and quiet drums move at an unhurried pace [R1]."
                        if english
                        else "钢琴与鼓的互动缓慢推进，音色柔和，节奏富有呼吸感[R1]。",
                        "vocals": "",
                        "mood": "The pace invites close listening [R1]."
                        if english
                        else "整体安静舒缓，适合慢慢聆听[R1]。",
                    },
                    "refs": ["R1"],
                    "missing": [],
                    "tool_requests": [],
                },
                usage=Usage(100, 100, 0),
            )
        if request.schema_name.startswith("short_review"):
            return CallResult(
                {
                    "text": (
                        "The piano and drums take turns shaping the melody, leaving quiet gaps in the recording where the decay of each note is audible [R1]. "
                        "The arrangement builds gradually and gives the slower passages a sense of movement, while keeping the volume low enough that the details are easy to follow."
                    )
                    if english
                    else "这张合成专辑的钢琴与鼓点轻柔交织，录音清晰而克制，旋律在宽松空间里自然展开，适合安静聆听，具体描述来自本次读取的测试资料[R1]。",
                    "refs": ["R1"],
                },
                usage=Usage(100, 100, 0),
            )
        if request.schema_name.startswith("select"):
            return CallResult(
                {
                    "order": [
                        {"c": row["c"], "verdict": "recommend", "note": "合成排序"}
                        for row in payload["candidates"]
                    ]
                },
                usage=Usage(100, 100, 0),
            )
        if request.schema_name.startswith("research"):
            if not payload["sources"]:
                urls = [row["url"] for row in payload["available_sources"]]
                return CallResult(
                    {
                        "action": "read" if urls else "search",
                        "query": "" if urls else payload["album"]["title"] + " album review",
                        "urls": urls,
                        "reason": "读取合成资料",
                        "assessment": None,
                    },
                    usage=Usage(100, 100, 0),
                )
            refs = [row["ref"] for row in payload["sources"]]
            return CallResult(
                {
                    "action": "finish",
                    "query": "",
                    "urls": [],
                    "reason": "合成资料足够",
                    "assessment": {
                        "reads": [
                            {"ref": ref, "about_target": "yes", "substantive": "yes"}
                            for ref in refs
                        ],
                        "score": 4,
                        "matches": [],
                        "tensions": [],
                        "unknowns": [],
                        "summary": "合成音乐资料说明编配与音色。",
                    },
                },
                usage=Usage(100, 100, 0),
            )
        raise AssertionError("UNEXPECTED_MODEL_STAGE:" + request.schema_name)

    async def search(self, binding, query, allowed_domains, blocked_domains=()):
        match = re.search(r"Synthetic Album (\d+)", query)
        assert match, "UNKNOWN_SYNTHETIC_ALBUM"
        number = int(match[1])
        self.event("search", album=number)
        return CallResult(
            (SearchHit(f"https://reviews.example/album-{number}", query),), usage=Usage(20, 20, 0)
        )

    async def fetch(self, url, max_bytes, timeout_seconds):
        number = int(url.rsplit("-", 1)[1])
        self.event("read", album=number)
        text = (
            f"Synthetic Album {number} by Synthetic Artist {number}. "
            + "The piano and soft drums form a quiet jazz recording with warm textures, "
            "subtle dynamics, spacious production and careful melodic development. " * 6
        )
        return FetchResponse(
            200,
            {"content-type": "text/html"},
            ("<html><body><article>" + text + "</article></body></html>").encode(),
        )

    async def close(self):
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    fixture = Fixtures(Path(args.config).resolve().parent)
    (fixture.root / "python-pid.json").write_text(json.dumps({"pid": os.getpid()}))
    original_request = service.ControlHTTPClient.request

    async def observed_request(self, execution_id, operation, payload):
        fixture.event("control", operation=operation)
        if operation == "invocation-grants":
            (fixture.root / "last-grants-request.json").write_text(
                json.dumps({"execution": execution_id, "payload": payload}), encoding="utf-8"
            )
        try:
            if (
                operation == "delivery"
                and payload["kind"] == "LONG_REVIEW"
                and fixture.controls().get("reorder_long")
                and "reorder" not in fixture.dropped
            ):
                fixture.dropped.add("reorder")
                await asyncio.sleep(0.2)
            result = await original_request(self, execution_id, operation, payload)
            faults = fixture.controls()
            if operation == "usage-receipt" and payload["outcome"] == "UNCERTAIN":

                async def settle_later():
                    while not fixture.controls().get("settle_unknown"):
                        await asyncio.sleep(0.05)
                    late = {
                        **payload,
                        "receipt_id": payload["receipt_id"] + ":late",
                        "outcome": "COMPLETED",
                        "finish": "COMPLETE",
                        "input_tokens": 100,
                        "cached_input_tokens": 0,
                        "output_tokens": 100,
                    }
                    try:
                        first = await original_request(self, execution_id, operation, late)
                        second = await original_request(self, execution_id, operation, late)
                        assert first == second
                        fixture.event("late_usage_idempotent")
                    except Exception as error:
                        fixture.event("late_usage_error", error=str(error))

                task = asyncio.create_task(settle_later())
                fixture.tasks.add(task)
                task.add_done_callback(fixture.tasks.discard)
            fault = faults.get("drop_operation")
            if operation == "delivery":
                fixture.event(
                    "delivery",
                    id=payload["delivery_id"],
                    delivery_kind=payload["kind"],
                    status=result["status"],
                    timing=payload["payload"].get("timing")
                    if isinstance(payload["payload"], dict)
                    else None,
                )
                (fixture.root / "last-delivery.json").write_text(
                    json.dumps({"execution": execution_id, "payload": payload}), encoding="utf-8"
                )
            if (
                operation == fault
                and (fault, faults.get("id")) not in fixture.dropped
                and (
                    operation != "delivery"
                    or not faults.get("drop_kind")
                    or faults["drop_kind"] == payload["kind"]
                )
            ):
                fixture.dropped.add((fault, faults.get("id")))
                fixture.event("response_lost", operation=operation)
                raise ControlUnavailable("CONTROL_UNAVAILABLE")
            return result
        except Exception as error:
            fixture.event("control_error", operation=operation, error=str(error))
            if operation == "delivery":
                # This standalone test service has synthetic outputs only; never used in serve.
                (fixture.root / "rejected-delivery.json").write_text(
                    json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
                )
            raise

    service.ControlHTTPClient.request = observed_request
    original_app = service.create_execution_app

    def observed_app(supervisor, control, factory, **kwargs):
        def observed_factory(start):
            pipeline = factory(start)

            async def run(execution):
                try:
                    await pipeline(execution)
                except Exception:
                    fixture.event("stage_error", traceback=traceback.format_exc())
                    raise

            return run

        app = original_app(supervisor, control, observed_factory, **kwargs)

        @web.middleware
        async def lose_admission(request, handler):
            response = await handler(request)
            faults = fixture.controls()
            if (
                faults.get("drop_admission")
                and "admission" not in fixture.dropped
                and request.method == "PUT"
                and re.fullmatch(r"/internal/v1/executions/[^/]+", request.path)
            ):
                fixture.dropped.add("admission")
                fixture.event("admission_response_lost")
                request.transport.close()
            return response

        app.middlewares.append(lose_admission)
        return app

    service.create_execution_app = observed_app
    service.OpenAIResponsesAdapter = lambda _: fixture
    service.ToolGateway = lambda *a, **kw: ToolGateway(fixture, **kw)
    args.managed_stdin = True
    service.serve(args)


if __name__ == "__main__":
    main()
