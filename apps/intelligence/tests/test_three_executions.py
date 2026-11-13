"""Three independent lifecycles, physical cleanup and boot-scoped wire evidence."""

import asyncio
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from auralisy_intelligence.agents import CallDenied, RoundSupervisor
from auralisy_intelligence.agents.demo import DemoHarness
from auralisy_intelligence.agents.http import create_execution_app
from auralisy_intelligence.contracts import (
    ContractError,
    content_digest,
    envelope,
    validate_message,
)
from test_agent_runtime import setup, start
from test_first_batch import running


def test_three_complete_rounds_keep_results_and_contexts_separate(tmp_path):
    async def run():
        supervisor = RoundSupervisor(boot_id="synthetic-python")
        harnesses = [DemoHarness(root=tmp_path / str(i), r=3, k=2, n=1) for i in range(3)]
        entered = set()
        ready = asyncio.Event()

        async def stage(execution, pipeline):
            entered.add(execution.id)
            if len(entered) == 3:
                ready.set()
            await ready.wait()
            assert len(supervisor.active) == 3
            await pipeline(execution)

        try:
            executions = []
            for i, harness in enumerate(harnesses):
                request = harness.request
                request["execution_id"] = f"round-{i}"
                request["erasure_handle"] = f"subject-{i}"
                request["payload_digest"] = content_digest(request)
                harness.control.register(request)
                pipeline = harness.pipeline(request, background=True)
                _, execution = await supervisor.accept(
                    request,
                    lambda execution, pipeline=pipeline: stage(execution, pipeline),
                    harness.control,
                )
                executions.append(execution)
            await asyncio.wait_for(asyncio.gather(*(supervisor.wait(e.id) for e in executions)), 10)
            for execution, harness in zip(executions, harnesses):
                assert execution.status == "CLOSED", execution.error_code
                assert execution.slot_released and not execution.dossiers
                assert execution.start["input"]["raw"] is None
                assert harness.control.cards
                assert all(
                    identifier.startswith(execution.id + ":")
                    for identifier in harness.control.deliveries
                )
            assert not supervisor.active
        finally:
            for harness in harnesses:
                harness.close()

    asyncio.run(run())


def test_three_http_executions_cancel_erase_and_shutdown_are_isolated():
    async def run():
        supervisor, control = setup(start())
        stopping, release = asyncio.Event(), asyncio.Event()
        entered = set()

        async def stage(execution):
            entered.add(execution.id)
            execution.foreground = execution.id != "one"
            try:
                await asyncio.Event().wait()
            finally:
                if execution.id == "one":
                    stopping.set()
                    await release.wait()

        app = create_execution_app(supervisor, control, lambda _: stage, token="test")
        async with running(app) as url, httpx.AsyncClient(base_url=url, trust_env=False) as http:
            http.headers.update(
                {"Authorization": "Bearer test", "X-Auralis-Audience": "intelligence"}
            )
            requests = {}

            async def put(identifier):
                request = start(identifier)
                request["erasure_handle"] = "subject-" + identifier
                request["payload_digest"] = content_digest(request)
                requests[identifier] = request
                return await http.put(
                    "/internal/v1/executions/" + identifier,
                    json=envelope("RoundStart", request, identifier),
                )

            async def runtime():
                message = (await http.get("/internal/v1/runtime")).json()
                validate_message(message)
                return message["payload"]

            assert [
                r.status_code
                for r in await asyncio.gather(*(put(i) for i in ("one", "two", "three")))
            ] == [201] * 3
            assert entered == {"one", "two", "three"}
            assert (await put("four")).status_code == 409
            assert (await put("two")).status_code == 200
            snapshot = await runtime()
            assert snapshot["execution_capacity"] == 3
            assert {row["execution_id"] for row in snapshot["active"]} == entered
            assert not supervisor.active["one"].cancelled.is_set()
            cancellation = asyncio.create_task(
                http.put(
                    "/internal/v1/executions/one/cancellation",
                    json=envelope(
                        "Cancellation",
                        {"boot": requests["one"]["boot"], "reason": "USER_CANCELLED"},
                        "cancel",
                    ),
                )
            )
            await asyncio.wait_for(stopping.wait(), 2)
            assert len((await runtime())["active"]) == 3
            assert (await put("four")).status_code == 409
            assert not any(supervisor.active[i].cancelled.is_set() for i in ("two", "three"))
            release.set()
            assert (await cancellation).json()["payload"]["slot_released"]
            assert (await put("four")).status_code == 201
            erased = await http.put(
                "/internal/v1/erasures/subject-two", json=envelope("Erasure", {"epoch": 1}, "erase")
            )
            assert erased.status_code == 200
            assert (await put("two")).json()["payload"]["code"] == "SUBJECT_REVOKED"
            assert {row["execution_id"] for row in (await runtime())["active"]} == {"three", "four"}
        assert not supervisor.active
        assert all(record[1].slot_released for record in supervisor.records.values())
        assert supervisor.maintenance

    asyncio.run(run())


def test_cancel_during_natural_cleanup_cannot_interrupt_physical_release():
    async def run():
        supervisor, control = setup(start())
        cleaning, release = asyncio.Event(), asyncio.Event()

        async def stage(execution):
            original = execution.cleanup

            async def cleanup():
                cleaning.set()
                await release.wait()
                await original()

            execution.cleanup = cleanup

        _, execution = await supervisor.accept(start(), stage, control)
        await cleaning.wait()
        with pytest.raises(ContractError, match="EXECUTOR_BUSY"):
            supervisor.forget(execution.id)
        cancellation = asyncio.create_task(
            supervisor.cancel(execution.id, execution.start["boot"], "SUBJECT_DELETED")
        )
        await asyncio.sleep(0)
        assert not execution.slot_released and not cancellation.done()
        release.set()
        assert (await cancellation)["slot_released"]
        assert not supervisor.active

    asyncio.run(run())


def test_cleanup_failure_remains_visible_and_holds_capacity():
    async def run():
        supervisor, control = setup(start())

        async def stage(execution):
            async def broken():
                raise OSError("synthetic cleanup failure")

            execution.cleanup = broken

        for identifier in ("one", "two", "three"):
            await supervisor.accept(start(identifier), stage, control)
            execution = await supervisor.wait(identifier)
            assert execution.error_code == "CLEANUP_FAILED"
            assert not execution.slot_released
        assert len(supervisor.active) == 3
        with pytest.raises(ContractError, match="EXECUTOR_BUSY"):
            await supervisor.accept(start("four"), stage, control)

    asyncio.run(run())


def test_dedup_capacity_expiry_and_restart_do_not_reexecute_known_intent():
    async def run():
        supervisor = RoundSupervisor(boot_id="python-test", max_records=1)
        _, control = setup(start())
        calls = []

        async def stage(execution):
            calls.append(execution.id)

        request = start()
        await supervisor.accept(request, stage, control)
        await supervisor.wait(request["execution_id"])
        assert (await supervisor.accept(request, stage, control))[0] == 200
        with pytest.raises(CallDenied, match="EXECUTION_RECORD_CAPACITY"):
            await supervisor.accept(start("next"), stage, control)
        supervisor.forget(request["execution_id"])
        query = {
            "python_boot_id": supervisor.boot_id,
            "payload_digest": request["payload_digest"],
            "original_deadline": request["deadlines"]["round_deadline_at"],
        }
        assert supervisor.admission(request["execution_id"], query)["status"] == "CLEARED"
        with pytest.raises(ContractError, match="EXECUTION_GONE"):
            await supervisor.accept(request, stage, control)
        rebooted = RoundSupervisor(boot_id="new-boot")
        assert rebooted.admission(request["execution_id"], query)["status"] == "UNKNOWN"
        with pytest.raises(ContractError, match="STALE_BOOT"):
            await rebooted.accept(request, stage, control)
        supervisor.cleared[request["execution_id"]] = (
            request["payload_digest"],
            datetime.now(timezone.utc) - timedelta(seconds=1),
        )
        await supervisor.accept(start("next"), stage, control)
        await supervisor.wait("next")
        assert calls == [request["execution_id"], "next"]
        assert not supervisor.cleared

    asyncio.run(run())
