"""Candidate admission is independent of resource slots; no long-review calls."""

import asyncio

from auralis_intelligence.agents.demo import DemoHarness, _reply
from auralis_intelligence.agents.resources import ServiceResources
from auralis_intelligence.contracts import content_digest, validate_definition


def test_all_fifty_enter_before_any_finishes_and_no_long_review_is_sent(tmp_path):
    async def run():
        harness = DemoHarness(root=tmp_path, r=50, k=15, n=3)
        try:
            harness.request["agent_policy"]["concurrency"]["search_max_in_flight"] = 1
            harness.request["agent_policy"]["digest"] = content_digest(
                harness.request["agent_policy"], "digest"
            )
            harness.request["payload_digest"] = content_digest(harness.request)
            pipeline = harness.pipeline(harness.request)
            pipeline.generate_long_reviews = False
            entered = set()
            ready = asyncio.Event()
            original = pipeline.research.run

            async def research(execution, candidate, *args, **kwargs):
                entered.add(candidate["release_group_id"])
                if len(entered) == 50:
                    ready.set()
                await asyncio.wait_for(ready.wait(), 3)
                return await original(execution, candidate, *args, **kwargs)

            pipeline.research.run = research

            def reply(binding, request):
                assert not request.schema_name.startswith("long_review")
                return _reply(binding, request)

            harness.adapter.replies = reply
            harness.control.register(harness.request)
            _, execution = await harness.supervisor.accept(
                harness.request, pipeline, harness.control
            )
            await harness.supervisor.wait(execution.id)
            assert len(entered) == 50
            assert execution.status == "CLOSED", execution.error_code
            batch = next(
                d["payload"]
                for d in harness.control.deliveries.values()
                if d["kind"] == "CARD_BATCH"
            )
            assert batch["analysis_mode"] == "PERSONALIZED"
            assert len(batch["cards"]) == 15
            assert all(c["long_review_state"] == "NOT_STARTED" for c in batch["cards"])
            assert batch["pending"]["long_reviews_generating"] == 0
            assert not any(d["kind"] == "LONG_REVIEW" for d in harness.control.deliveries.values())
        finally:
            harness.close()

    asyncio.run(run())


def test_null_limits_admit_all_resources_and_same_host_and_cancel_cleanly():
    async def run():
        service = ServiceResources(model=50, search=50, read=50, per_host=50, pending=200)
        scheduler = service.scope(
            "test", "session", {"model": None, "search": None, "read": None, "per_host": None}
        )
        entered = asyncio.Queue()

        async def work():
            await entered.put(True)
            await asyncio.Event().wait()

        tasks = [
            asyncio.create_task(
                scheduler.run(resource, work, host="same.example" if resource == "read" else None)
            )
            for resource in ("model", "search", "read")
            for _ in range(50)
        ]
        try:
            for _ in tasks:
                await asyncio.wait_for(entered.get(), 3)
            assert service.counts == {"model": 50, "search": 50, "read": 50, "cpu": 0}
            assert service.hosts == {"same.example": 50}
        finally:
            await service.close()
            await asyncio.gather(*tasks, return_exceptions=True)
        assert not service.running and not service.pending
        assert all(value == 0 for value in service.counts.values())

    asyncio.run(run())


def test_round_contract_accepts_explicit_null_concurrency(tmp_path):
    harness = DemoHarness(root=tmp_path)
    try:
        policy = harness.request["agent_policy"]
        for key in (
            "model_max_in_flight",
            "search_max_in_flight",
            "read_max_in_flight",
            "read_max_per_host",
        ):
            policy["concurrency"][key] = None
        policy["digest"] = content_digest(policy, "digest")
        harness.request["payload_digest"] = content_digest(harness.request)
        validate_definition("RoundStart", harness.request)
    finally:
        harness.close()
