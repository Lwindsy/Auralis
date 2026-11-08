"""Service-wide contention, fair admission and physical cancellation."""

import asyncio
import threading

import pytest
from auralis_intelligence.agents.ports import CallDenied
from auralis_intelligence.agents.resources import ServiceResources


def test_global_limit_and_independent_close():
    async def scenario():
        service = ServiceResources(model=2, background=1)
        scopes = [service.scope(str(i), str(i)) for i in range(3)]
        gate = asyncio.Event()
        started = []

        async def work(i):
            started.append(i)
            await gate.wait()

        jobs = [
            asyncio.create_task(s.run("model", lambda i=i: work(i))) for i, s in enumerate(scopes)
        ]
        for _ in range(5):
            await asyncio.sleep(0)
        assert len(started) == 2
        assert service.counts["model"] == 2
        await scopes[0].close()
        for _ in range(5):
            await asyncio.sleep(0)
        assert started == [0, 1, 2]
        assert not jobs[1].done()
        gate.set()
        await asyncio.gather(*jobs, return_exceptions=True)
        await service.close()
        assert not any(service.counts.values())

    asyncio.run(scenario())


def test_session_rotation_and_background_opportunity():
    async def scenario():
        service = ServiceResources(model=1, background=1)
        a, b = service.scope("a", "a"), service.scope("b", "b")
        gate = asyncio.Event()
        order = []
        first = asyncio.create_task(a.run("model", gate.wait))
        await asyncio.sleep(0)

        async def work(name):
            order.append(name)

        jobs = [
            asyncio.create_task(a.run("model", lambda: work("a"), priority=-10)) for _ in range(4)
        ]
        jobs += [
            asyncio.create_task(b.run("model", lambda: work("b"))),
            asyncio.create_task(b.run("model", lambda: work("background"), background=True)),
        ]
        await asyncio.sleep(0)
        gate.set()
        await asyncio.gather(first, *jobs)
        assert order[0] == "background"
        assert order.index("b") < 3
        await service.close()

    asyncio.run(scenario())


def test_shared_search_model_rate_and_cancelled_waiter():
    async def scenario():
        service = ServiceResources(rpm=1, tpm=10, window_seconds=0.03)
        a, b = service.scope("a", "a"), service.scope("b", "b")
        sent = []

        async def work(name):
            sent.append(name)

        await a.run("search", lambda: work("search"), tokens=5)
        waiting = asyncio.create_task(b.run("model", lambda: work("cancelled"), tokens=5))
        await asyncio.sleep(0)
        assert sent == ["search"]
        waiting.cancel()
        await asyncio.gather(waiting, return_exceptions=True)
        await b.run("model", lambda: work("model"), tokens=10)
        assert sent == ["search", "model"]
        await service.close()

    asyncio.run(scenario())


def test_queue_bounds_and_host_limit():
    async def scenario():
        service = ServiceResources(per_host=1, pending=1)
        a, b = service.scope("a", "a"), service.scope("b", "b")
        gate = asyncio.Event()
        one = asyncio.create_task(a.run("read", gate.wait, host="example.org"))
        await asyncio.sleep(0)
        two = asyncio.create_task(b.run("read", gate.wait, host="example.org"))
        await asyncio.sleep(0)
        assert service.counts["read"] == 1
        with pytest.raises(CallDenied, match="RESOURCE_QUEUE_FULL"):
            await a.run("read", gate.wait, host="example.org")
        gate.set()
        await asyncio.gather(one, two)
        await service.close()

    asyncio.run(scenario())


def test_cpu_cancellation_drains_physical_worker():
    async def scenario():
        service = ServiceResources(cpu=1)
        a, b = service.scope("a", "a"), service.scope("b", "b")
        entered, gate = threading.Event(), threading.Event()

        def compute():
            entered.set()
            gate.wait(2)

        first = asyncio.create_task(a.cpu(compute))
        while not entered.is_set():
            await asyncio.sleep(0.001)
        first.cancel()
        for _ in range(5):
            await asyncio.sleep(0)
        assert not first.done()
        assert service.counts["cpu"] == 1
        second = asyncio.create_task(b.cpu(lambda: 42))
        await asyncio.sleep(0)
        assert not second.done()
        gate.set()
        await asyncio.gather(first, return_exceptions=True)
        assert await second == 42
        await service.close()

    asyncio.run(scenario())


def test_borrowed_background_is_not_preempted():
    async def scenario():
        service = ServiceResources(model=4, background=2)
        scopes = [service.scope(str(i), str(i)) for i in range(3)]
        gates = [asyncio.Event() for _ in range(4)]
        jobs = [
            asyncio.create_task(scopes[i % 2].run("model", gates[i].wait, background=True))
            for i in range(4)
        ]
        for _ in range(5):
            await asyncio.sleep(0)
        assert service.counts["model"] == 4
        entered = asyncio.Event()

        async def foreground():
            entered.set()

        front = asyncio.create_task(scopes[2].run("model", foreground))
        await asyncio.sleep(0)
        assert not entered.is_set() and not any(job.done() for job in jobs)
        gates[0].set()
        await asyncio.wait_for(entered.wait(), 1)
        for gate in gates:
            gate.set()
        await asyncio.gather(*jobs, front)
        await service.close()

    asyncio.run(scenario())


def test_cancel_before_factory_starts_releases_slot():
    async def scenario():
        service = ServiceResources(model=1, background=1)
        scope = service.scope("a", "a")

        async def unreachable():
            pytest.fail("cancelled work must not start")

        task = asyncio.create_task(scope.run("model", unreachable))
        await asyncio.sleep(0)
        await scope.close()
        await asyncio.gather(task, return_exceptions=True)
        assert service.counts["model"] == 0
        await service.close()

    asyncio.run(scenario())


def test_rate_reservation_does_not_expire_while_grant_or_send_is_running():
    async def scenario():
        service = ServiceResources(rpm=1, tpm=10, window_seconds=0.01)
        scope = service.scope("a", "a")
        gate = asyncio.Event()
        one = asyncio.create_task(scope.run("search", gate.wait, tokens=10))
        await asyncio.sleep(0.03)
        entered = asyncio.Event()
        two = asyncio.create_task(scope.run("model", entered.wait, tokens=10))
        await asyncio.sleep(0)
        assert service.counts["model"] == 0
        two.cancel()
        gate.set()
        await asyncio.gather(one, two, return_exceptions=True)
        await service.close()

    asyncio.run(scenario())


def test_resource_wait_cancellation_never_requests_budget_or_sends():
    from auralis_intelligence.agents.runtime import Execution
    from test_agent_runtime import start

    async def scenario():
        service = ServiceResources(model=1, background=1)
        gate = asyncio.Event()
        blocker = asyncio.create_task(service.scope("other", "other").run("model", gate.wait))
        await asyncio.sleep(0)

        class Control:
            async def request(self, *args):
                pytest.fail("waiting work must not reserve money")

        execution = Execution(start(), Control())
        execution.scheduler = service.scope(execution.id, "waiting")
        plan = execution.invocation_plan("SCREEN", "screen", {"test": True})

        async def send():
            pytest.fail("waiting work must not send")

        invocation = asyncio.create_task(execution.invoke(plan, send, timeout_seconds=1))
        await asyncio.sleep(0)
        invocation.cancel()
        await asyncio.gather(invocation, return_exceptions=True)
        assert execution.calls[plan["invocation_id"]]["sent_at"] is None
        assert not execution.uncertain and not execution.pending_usage
        gate.set()
        await blocker
        await service.close()

    asyncio.run(scenario())


def test_quota_groups_share_within_group_and_are_independent_across_groups():
    async def scenario():
        service = ServiceResources(
            quota_groups={"large": {"rpm": 1, "tpm": 10}, "luna": {"rpm": 1, "tpm": 20}},
            binding_groups={"astra": "large", "sol": "large", "terra": "large", "luna": "luna"},
        )
        a, b = service.scope("a", "a"), service.scope("b", "b")
        sent = []

        async def work(name):
            sent.append(name)

        await a.run(
            "search",
            lambda: work("astra"),
            tokens=10,
            quota_group=service.group_for_binding("astra"),
        )
        blocked = [
            asyncio.create_task(
                b.run(
                    "model",
                    lambda: work("blocked"),
                    tokens=1,
                    quota_group=service.group_for_binding(name),
                )
            )
            for name in ("sol", "terra")
        ]
        await asyncio.sleep(0)
        await b.run(
            "model", lambda: work("luna"), tokens=20, quota_group=service.group_for_binding("luna")
        )
        assert sent == ["astra", "luna"]
        assert not any(task.done() for task in blocked)
        with pytest.raises(CallDenied, match="MODEL_QUOTA_NOT_CONFIGURED"):
            service.group_for_binding("unknown")
        await service.close()
        await asyncio.gather(*blocked, return_exceptions=True)

    asyncio.run(scenario())


def test_example_quota_mapping_covers_all_configured_bindings():
    import json
    from pathlib import Path

    from auralis_intelligence.providers.config import load_bindings

    root = Path(__file__).resolve().parents[1]
    config = json.loads((root / "agent-service.example.json").read_text())
    resources = dict(config["service_resources"])
    mapping = resources.pop("model_groups")
    bindings = load_bindings(root / config["model_bindings"])
    service = ServiceResources(
        **resources,
        binding_groups={ref: mapping[binding.model] for ref, binding in bindings.items()},
    )
    assert service.quotas == {
        "astra_sol_terra": {"rpm": 5000, "tpm": 1000000},
        "luna": {"rpm": 5000, "tpm": 2000000},
    }
    assert service.group_for_binding("long-write-terra") == "astra_sol_terra"
    assert service.group_for_binding("screen-gpt-6-luna-closeout") == "luna"
    asyncio.run(service.close())
