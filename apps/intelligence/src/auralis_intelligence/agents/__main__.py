"""Standalone offline demo and explicit live-config loopback service."""

import argparse
import asyncio
import json
import logging
import os
from pathlib import Path

from aiohttp import web
from auralis_intelligence.preferences import ReferenceCatalog
from auralis_intelligence.providers import ModelGateway, OpenAIResponsesAdapter, ToolGateway
from auralis_intelligence.providers.config import load_bindings
from auralis_intelligence.recall import WeightConfig

from .background import RoundPipeline
from .demo import DemoHarness
from .http import ControlHTTPClient, create_execution_app
from .recall_stage import RecallStage
from .resources import ServiceResources
from .runtime import RoundSupervisor


async def demo(args):
    harness = DemoHarness(scenario=args.scenario, r=args.r, k=args.k, n=args.n)
    try:
        summary = await harness.run(include_calls=args.include_calls, background=args.background)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return (
            0
            if summary["execution_status"] == "CLOSED"
            and summary["first_outcome"] not in {"FAILED", "EXPIRED", "NEEDS_INPUT"}
            else 1
        )
    finally:
        harness.close()


def serve(args):
    config_path = Path(args.config).resolve()
    config = json.loads(config_path.read_text(encoding="utf-8"))

    def relative(name):
        return (config_path.parent / config[name]).resolve()

    service_token = os.environ.get(config["service_token_env"], "")
    control = ControlHTTPClient(
        config["control_url"], os.environ.get(config["control_token_env"], "")
    )
    adapter = OpenAIResponsesAdapter(os.environ.get(config["provider_key_env"], ""))
    configured_bindings = load_bindings(relative("model_bindings"))
    resource_config = dict(config["service_resources"])
    model_groups = resource_config.pop("model_groups")
    if not resource_config.get("quota_groups"):
        raise ValueError("PROVIDER_QUOTA_REQUIRED")
    if any(binding.model not in model_groups for binding in configured_bindings.values()):
        raise ValueError("MODEL_QUOTA_NOT_CONFIGURED")
    resources = ServiceResources(
        **resource_config,
        binding_groups={
            ref: model_groups[binding.model] for ref, binding in configured_bindings.items()
        },
    )
    configured_roles = json.loads(relative("model_bindings").read_text(encoding="utf-8"))
    if "long_write" not in configured_roles:
        raise ValueError("LONG_WRITE_MODEL_BINDING_REQUIRED")
    model = ModelGateway(
        adapter,
        configured_bindings,
        trial_trace_directory=relative("trial_trace_directory")
        if config.get("trial_trace_directory")
        else None,
    )
    reference = ReferenceCatalog.load(relative("reference"))
    from auralis_intelligence.album_reviews.batch import (
        BatchResearchStage,
        BatchSelectStage,
        BatchWriteStage,
    )
    from auralis_intelligence.album_reviews.prepared import PreparedEvidence
    from auralis_intelligence.preferences.fast import FastPreferencesStage
    from auralis_intelligence.recall import load_a_v2

    # Review discovery is request-local. No saved URL/excerpt file is required for live mode.
    mode = config.get("evidence_mode", "live")
    if mode not in {"live", "prepared"}:
        raise ValueError("UNKNOWN_EVIDENCE_MODE")
    prepared = PreparedEvidence(relative("prepared_evidence")) if mode == "prepared" else None
    engine = load_a_v2(relative("recall_snapshot"), weights=WeightConfig())
    from auralis_intelligence.recall.full_audience import FullAudience

    engine.bind_full_audience(
        FullAudience(relative("audience_snapshot"), config["audience_binding"])
    )
    engine.audience([])
    long_tools = ToolGateway(search_adapter=adapter, model_gateway=model)

    def factory(start):
        if "audience_min_listeners" not in start["agent_policy"]:
            raise ValueError("AUDIENCE_POLICY_REQUIRED")
        if mode == "live":
            from auralis_intelligence.album_reviews.cold import ColdEvidence

            evidence = ColdEvidence(overlap=True, sources_per_album=1, race=True)
            tools = ToolGateway(search_adapter=adapter, model_gateway=model)
        else:
            evidence = prepared
            tools = ToolGateway(evidence, model_gateway=model)
        from auralis_intelligence.album_reviews.seed_context import SeedContext, SeededPipeline

        seeds = SeedContext(model, long_tools)
        batch = BatchResearchStage(
            seeds,
            tools,
            evidence,
            genre_names={i: r["name"] for i, r in reference.genres.items()},
            candidate_limit=start["selection_config"]["shortlist_target_count"],
        )
        pipeline = RoundPipeline(
            FastPreferencesStage(model, reference),
            RecallStage(
                relative("recall_snapshot"),
                binding=start["recall_binding"],
                weights=WeightConfig(),
                engine=engine,
                min_listeners=start["agent_policy"]["audience_min_listeners"],
            ),
            batch,
            BatchWriteStage(seeds, long_tools, batch=batch),
            BatchSelectStage(seeds, batch=batch),
        )

        return SeededPipeline(pipeline, seeds)

    app = create_execution_app(
        RoundSupervisor(
            resources=resources, receipt_timeout_seconds=config.get("receipt_timeout_seconds", 1.0)
        ),
        control,
        factory,
        token=service_token,
    )

    async def cleanup(app):
        await resources.close()
        await control.close()
        await adapter.close()

    app.on_cleanup.append(cleanup)
    if args.managed_stdin:
        from .managed import manage_stdin

        manage_stdin(app)
    web.run_app(app, host="127.0.0.1", port=args.port, access_log=None, print=None)


def main() -> int:
    parser = argparse.ArgumentParser(description="Auralis standalone agent")
    commands = parser.add_subparsers(dest="command", required=True)
    local = commands.add_parser(
        "demo", help="synthetic models/pages, real local A-V2, no paid calls"
    )
    local.add_argument(
        "--scenario", choices=["mixed", "album", "genre", "text", "no-positive"], default="mixed"
    )
    local.add_argument("--r", type=int, default=50)
    local.add_argument("--k", type=int, default=15)
    local.add_argument("--n", type=int, default=3)
    local.add_argument(
        "--include-calls",
        action="store_true",
        help="include safe synthetic invocation and usage records",
    )
    local.add_argument(
        "--background",
        action="store_true",
        help="run background supply, idle close and the round report",
    )
    server = commands.add_parser("serve", help="explicit configured loopback service")
    server.add_argument("--config", required=True)
    server.add_argument("--port", type=int, default=8765)
    server.add_argument("--managed-stdin", action="store_true")
    args = parser.parse_args()
    if hasattr(__import__("sys").stdout, "reconfigure"):
        __import__("sys").stdout.reconfigure(encoding="utf-8")
    if args.command == "demo":
        return asyncio.run(demo(args))
    # Only application diagnostics: never provider bodies, paths or exception messages.
    logging.basicConfig(
        level=logging.WARNING, format="%(name)s %(levelname)s %(message)s", force=True
    )
    for handler in logging.getLogger().handlers:
        handler.addFilter(lambda record: record.name.startswith("auralis_intelligence"))
    try:
        serve(args)
    except Exception as error:
        code = (
            str(error)
            if isinstance(error, ValueError)
            and str(error)
            in {
                "PROVIDER_QUOTA_REQUIRED",
                "MODEL_QUOTA_NOT_CONFIGURED",
                "LONG_WRITE_MODEL_BINDING_REQUIRED",
                "UNKNOWN_EVIDENCE_MODE",
                "LOOPBACK_CONTROL_CONFIGURATION_REQUIRED",
                "SERVICE_TOKEN_REQUIRED",
                "INVALID_RESOURCE_LIMITS",
                "INVALID_PROVIDER_QUOTA",
                "UNKNOWN_QUOTA_GROUP",
            }
            else type(error).__name__
        )
        print("AURALIS_AGENT_STARTUP_FAILED " + code, file=__import__("sys").stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
