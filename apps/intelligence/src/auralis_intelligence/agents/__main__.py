"""Standalone offline demo and explicit live-config loopback service."""

import argparse
import asyncio
import json
import os
from pathlib import Path

from aiohttp import web

from auralis_intelligence.album_reviews import ResearchStage, WriteStage
from auralis_intelligence.preferences import PreferencesStage, ReferenceCatalog
from auralis_intelligence.providers import ModelGateway, OpenAIResponsesAdapter, ToolGateway
from auralis_intelligence.providers.config import load_bindings
from auralis_intelligence.ranking import SelectStage
from auralis_intelligence.recall import WeightConfig
from .demo import DemoHarness
from .http import ControlHTTPClient, create_execution_app
from .pipeline import FirstBatchPipeline
from .recall_stage import RecallStage
from .runtime import RoundSupervisor


async def demo(args):
    harness = DemoHarness(scenario=args.scenario, r=args.r, k=args.k, n=args.n)
    try:
        summary = await harness.run(include_calls=args.include_calls)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0 if summary["execution_status"] == "CLOSED" and summary["first_outcome"] not in {"FAILED", "EXPIRED", "NEEDS_INPUT"} else 1
    finally:
        harness.close()


def serve(args):
    config_path = Path(args.config).resolve()
    config = json.loads(config_path.read_text(encoding="utf-8"))
    relative = lambda name: (config_path.parent / config[name]).resolve()
    service_token = os.environ.get(config["service_token_env"], "")
    control = ControlHTTPClient(config["control_url"], os.environ.get(config["control_token_env"], ""))
    adapter = OpenAIResponsesAdapter(os.environ.get(config["provider_key_env"], ""))
    model = ModelGateway(adapter, load_bindings(relative("model_bindings")))
    reference = ReferenceCatalog.load(relative("reference"))
    tools = ToolGateway(search_adapter=adapter, model_gateway=model)
    def factory(start):
        return FirstBatchPipeline(PreferencesStage(model, reference), RecallStage(relative("recall_snapshot"), binding=start["recall_binding"], weights=WeightConfig(**start["agent_policy"]["recall"]["weights"])),
                                  ResearchStage(model, tools), WriteStage(model, tools), SelectStage(model))
    app = create_execution_app(RoundSupervisor(), control, factory, token=service_token)
    async def cleanup(app):
        await control.close()
        await adapter.close()
    app.on_cleanup.append(cleanup)
    web.run_app(app, host="127.0.0.1", port=args.port, access_log=None)


def main() -> int:
    parser = argparse.ArgumentParser(description="Auralis standalone agent")
    commands = parser.add_subparsers(dest="command", required=True)
    local = commands.add_parser("demo", help="synthetic models/pages, real local A-V2, no paid calls")
    local.add_argument("--scenario", choices=["mixed", "album", "genre", "text", "no-positive"], default="mixed")
    local.add_argument("--r", type=int, default=20)
    local.add_argument("--k", type=int, default=8)
    local.add_argument("--n", type=int, default=3)
    local.add_argument("--include-calls", action="store_true", help="include safe synthetic invocation and usage records")
    server = commands.add_parser("serve", help="explicit configured loopback service")
    server.add_argument("--config", required=True)
    server.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if hasattr(__import__("sys").stdout, "reconfigure"):
        __import__("sys").stdout.reconfigure(encoding="utf-8")
    if args.command == "demo":
        return asyncio.run(demo(args))
    serve(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
