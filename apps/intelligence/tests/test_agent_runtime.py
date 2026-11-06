"""Runtime fault scenarios: physical cancellation, authorization and interval time."""

import asyncio
from copy import deepcopy
import json
from pathlib import Path

import pytest

from auralis_intelligence.agents import AgentClock, CallDenied, CallResult, ControlUnavailable, RoundSupervisor, Usage
from auralis_intelligence.agents.control_double import JavaControlDouble
from auralis_intelligence.agents.scheduler import BoundedScheduler
from auralis_intelligence.contracts import ContractError, content_digest, digest, validate_definition


BASE = json.loads((Path(__file__).parent / "fixtures/agent-contract-cases.json").read_text(encoding="utf-8"))[0]["message"]["payload"]


def start(execution_id="execution-1"):
    result = deepcopy(BASE)
    result["execution_id"] = execution_id
    result["boot"] = {"python_boot_id": "python-test", "coordinator_boot_id": "java-test"}
    result["model_bindings"] = {role: {**binding, "ref": role, "max_input_tokens": 100, "max_output_tokens": 100} for role, binding in result["model_bindings"].items()}
    result["source_policy"]["rules"] = [{"match": {"domain_suffix": domain}, "decision": "ALLOW", "uses": ["READ", "AI_PROCESS", "STORE_LOCATOR"], "reason": "synthetic-test"} for domain in ("one.example", "two.example")]
    result["payload_digest"] = content_digest(result)
    return result


def setup(request):
    control = JavaControlDouble("java-test", catalog={"album-1": {"title": "Album", "artist_credit": "Artist", "first_release_year": 2000, "release_type": "Album", "primary_genres": []}})
    control.register(request)
    return RoundSupervisor(boot_id="python-test"), control


async def bind(execution, control):
    raw = execution.start["input"]["raw"]
    snapshot = {"resolution_id": "resolution-1", "input_digest": raw["input_digest"], "reference_revision": "genres-1", "parser_revision": "parse-1", "seeds": [], "tags": [{"id": "jazz", "strength": "normal", "focused": False}], "disliked_tags": [], "forbidden_tags": [], "required_tags": [], "soft_preferences": [{"preference_id": "mood", "description": "适合深夜独处，尽量避免喧闹", "kind": "context", "basis": "explicit", "strength": "normal", "focused": False}], "overrides": [], "mode": "explore", "has_positive_signal": True}
    snapshot["effective_preference_digest"] = content_digest(snapshot, "effective_preference_digest")
    control.preference_resolver = lambda raw, proposal: snapshot
    proposal = {"input_digest": raw["input_digest"], "reference_revision": "genres-1", "parser_revision": "parse-1", "preferences": [], "constraints": [], "unresolved_constraints": [], "soft_preferences": snapshot["soft_preferences"]}
    proposal["proposal_digest"] = content_digest(proposal, "proposal_digest")
    accepted = await execution.control_request("preference-resolution", proposal)
    recall = {"recall_binding": execution.start["recall_binding"], "input_digest": raw["input_digest"], "effective_preference_digest": accepted["effective_preference_digest"], "exclusion_digest": execution.start["input"]["exclusion_digest"], "requested_count": 20, "returned_count": 1, "execution_status": "COMPLETE", "raw_reason": "insufficient_candidates", "shortfall": 19, "truncated": False, "candidates": [{"release_group_id": "album-1", "recall_rank": 1}], "source_dependencies": []}
    recall["proposal_digest"] = content_digest(recall, "proposal_digest")
    bundle = await execution.control_request("candidate-validation", recall)
    execution.binding = bundle["binding"]
    return bundle


def supply():
    return {"state": "R_EXHAUSTED", "counts": {"unresearched": 0, "reserve": 0, "in_flight": 0, "ready_unshown_known": 0}, "reason_code": None}


def test_fake_stage_admission_grant_delivery_close_and_clean_memory():
    async def run():
        request = start()
        supervisor, control = setup(request)
        async def stage(execution):
            await bind(execution, control)
            execution.dossiers["album-1"] = {"body": "private synthetic source"}
            plan = execution.invocation_plan("SCREEN", "screen", {"user": "private input"})
            async def send(): return CallResult({"score": 4}, usage=Usage(4, 6, 0))
            result = await execution.invoke(plan, send, timeout_seconds=1)
            assert result.output == {"score": 4}
            await execution.deliver("SUPPLY_STATUS", supply())
        code, execution = await supervisor.accept(request, stage, control)
        assert code == 201
        assert (await supervisor.accept(request, stage, control))[0] == 200
        await supervisor.wait(execution.id)
        assert execution.status == "CLOSED", execution.error_code
        assert execution.status == "CLOSED" and execution.slot_released
        assert supervisor.active is None and not execution.dossiers and not execution.buffer
        assert execution.final_timing is not None and "agent_wait_ms" in execution.final_timing
        assert execution.start["input"]["raw"] is None
        assert control.sessions[request["budget"]["session_budget_scope_id"]].settled == 10
        assert execution.view()["lanes"]["FOREGROUND"]["settled_microusd"] == 10
        serialized = json.dumps(execution.events, ensure_ascii=False)
        assert "private" not in serialized and "爵士" not in serialized
        assert len(control.deliveries) == 1
    asyncio.run(run())


def test_foreground_busy_old_boot_and_changed_duplicate_rejected():
    async def run():
        request = start()
        supervisor, control = setup(request)
        waiting = asyncio.Event()
        async def stage(execution): await waiting.wait()
        _, execution = await supervisor.accept(request, stage, control)
        with pytest.raises(ContractError, match="EXECUTOR_BUSY"):
            await supervisor.accept(start("execution-2"), stage, control)
        changed = deepcopy(request)
        changed["run_id"] = "different"
        changed["payload_digest"] = content_digest(changed)
        with pytest.raises(ContractError, match="IDEMPOTENCY_CONFLICT"):
            await supervisor.accept(changed, stage, control)
        changed = start("execution-2")
        changed["boot"]["python_boot_id"] = "old"
        changed["payload_digest"] = content_digest(changed)
        with pytest.raises(ContractError, match="STALE_BOOT"):
            await supervisor.accept(changed, stage, control)
        await supervisor.cancel(execution.id, request["boot"], "USER_CANCELLED")
        assert execution.slot_released
    asyncio.run(run())


def test_cancellation_waits_for_physical_stop_and_propagates_to_provider():
    async def run():
        request = start()
        supervisor, control = setup(request)
        sending, stopping, stopped = asyncio.Event(), asyncio.Event(), asyncio.Event()
        async def send():
            sending.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                stopping.set()
                await stopped.wait()
                raise
        async def stage(execution):
            await execution.invoke(execution.invocation_plan("WRITE_LONG", "write", {}, lane="BACKGROUND"), send, timeout_seconds=60)
        _, execution = await supervisor.accept(request, stage, control)
        await sending.wait()
        cancellation = asyncio.create_task(supervisor.cancel(execution.id, request["boot"], "PREEMPTED"))
        await stopping.wait()
        assert not execution.slot_released and supervisor.active is execution
        with pytest.raises(ContractError, match="EXECUTOR_BUSY"):
            await supervisor.accept(start("next"), stage, control)
        stopped.set()
        view = await cancellation
        assert view["slot_released"] and view["status"] == "CANCELLED"
        account = control.sessions[request["budget"]["session_budget_scope_id"]]
        assert account.reserved == 200 and account.settled == 0
        assert len(execution.uncertain) == 1
    asyncio.run(run())


@pytest.mark.parametrize("lost_after_commit", [False, True])
def test_unreachable_or_lost_grant_never_sends_or_rebuys(lost_after_commit):
    async def run():
        request = start()
        supervisor, control = setup(request)
        (control.lose_response_once if lost_after_commit else control.unavailable).add("invocation-grants")
        calls = 0
        async def send():
            nonlocal calls
            calls += 1
            return CallResult()
        async def stage(execution):
            plan = execution.invocation_plan("SCREEN", "screen", {})
            with pytest.raises(CallDenied, match="GRANT_UNCERTAIN"):
                await execution.invoke(plan, send, timeout_seconds=1)
            with pytest.raises(CallDenied, match="ALREADY_CLAIMED"):
                await execution.invoke(plan, send, timeout_seconds=1)
        _, execution = await supervisor.accept(request, stage, control)
        await supervisor.wait(execution.id)
        assert execution.status == "CLOSED", execution.error_code
        assert calls == 0
        assert control.sessions[request["budget"]["session_budget_scope_id"]].reserved == (200 if lost_after_commit else 0)
    asyncio.run(run())


def test_delivery_response_loss_reuses_id_and_original_payload():
    async def run():
        request = start()
        supervisor, control = setup(request)
        control.lose_response_once.add("delivery")
        async def stage(execution):
            await bind(execution, control)
            with pytest.raises(ControlUnavailable):
                await execution.deliver("SUPPLY_STATUS", supply())
            assert list(execution.buffer) == [execution.id + ":1"]
            assert len(control.deliveries) == 1
            await execution.redeliver()
            assert not execution.buffer and len(control.deliveries) == 1
            original = deepcopy(control.deliveries[execution.id + ":1"])
            original["payload"]["state"] = "SUFFICIENT"
            original["payload_digest"] = digest(original["payload"])
            with pytest.raises(ContractError, match="IDEMPOTENCY_CONFLICT"):
                await control.request(execution.id, "delivery", original)
        _, execution = await supervisor.accept(request, stage, control)
        await supervisor.wait(execution.id)
        assert execution.status == "CLOSED", execution.error_code
        assert execution.status == "CLOSED"
    asyncio.run(run())


def test_round_budget_and_structure_repair_survive_new_execution():
    async def run():
        request = start()
        supervisor, control = setup(request)
        async def stage(execution):
            async def send(): return CallResult(usage=Usage(2, 3, 0))
            plan = execution.invocation_plan("STRUCTURE_REPAIR", "write", {}, lane="BACKGROUND")
            await execution.invoke(plan, send, timeout_seconds=1)
        _, execution = await supervisor.accept(request, stage, control)
        await supervisor.wait(execution.id)
        assert execution.status == "CLOSED", execution.error_code
        next_start = start("execution-2")
        control.register(next_start)
        async def next_stage(execution):
            async def send(): pytest.fail("repair must not send twice")
            with pytest.raises(CallDenied, match="REPAIR_QUOTA_EXHAUSTED"):
                await execution.invoke(execution.invocation_plan("STRUCTURE_REPAIR", "write", {}, lane="BACKGROUND"), send, timeout_seconds=1)
        _, next_execution = await supervisor.accept(next_start, next_stage, control)
        await supervisor.wait(next_execution.id)
        assert control.sessions[request["budget"]["session_budget_scope_id"]].settled == 5
        assert next_execution.status == "CLOSED"
    asyncio.run(run())


def test_budget_denial_and_usage_receipt_loss_do_not_duplicate_charge():
    async def run():
        request = start()
        supervisor, control = setup(request)
        control.lose_response_once.add("usage-receipt")
        async def stage(execution):
            async def send(): return CallResult(usage=Usage(4, 6, 0))
            await execution.invoke(execution.invocation_plan("SCREEN", "screen", {}), send, timeout_seconds=1)
            assert execution.pending_usage
            await execution.flush_usage()
            assert not execution.pending_usage
            control.sessions[request["budget"]["session_budget_scope_id"]].cap = 200
            plan = execution.invocation_plan("SCREEN", "screen", {})
            with pytest.raises(CallDenied, match="BUDGET_EXHAUSTED"):
                await execution.invoke(plan, send, timeout_seconds=1)
        _, execution = await supervisor.accept(request, stage, control)
        await supervisor.wait(execution.id)
        assert execution.status == "CLOSED", execution.error_code
        account = control.sessions[request["budget"]["session_budget_scope_id"]]
        assert account.settled == 10 and account.reserved == 0
    asyncio.run(run())


def test_source_revocation_is_checked_even_without_push():
    async def run():
        request = start()
        _, control = setup(request)
        authorization = {"read_id": "r1", "candidate_id": "album-1", "url": "https://one.example/review", "purpose": "SCREEN_RESEARCH", "policy_epoch": 0}
        assert (await control.request("execution-1", "read-authorization", authorization))["decision"] == "GRANTED"
        receipt = {"read_id": "r1", "candidate_id": "album-1", "purpose": "SCREEN_RESEARCH", "requested_url": authorization["url"], "final_url": authorization["url"], "publisher_key": "one.example", "status": "READ_OK", "failure_reason": None, "read_at": "2026-11-07T00:00:00Z", "content_digest": digest("synthetic"), "text_chars": 1000, "near_duplicate_of": None, "checks": {"mentions_title": True, "mentions_artist": True}, "source_policy_epoch": 0}
        assert (await control.request("execution-1", "read-receipts", {"items": [receipt]}))["accepted_read_ids"] == ["r1"]
        control.policy["rules"][0]["decision"] = "DENY"
        control.policy["policy_epoch"] += 1
        authorization["read_id"] = "r2"
        assert (await control.request("execution-1", "read-authorization", authorization))["decision"] == "DENIED"
        plan = {"invocation_id": "execution-1:inv:1", "attempt": 1, "purpose": "SCREEN", "cost_class": "MODEL", "lane": "FOREGROUND", "run_id": "run-1", "candidate_id": "album-1", "read_ids": ["r1"], "model_binding_ref": "screen", "max_input_tokens": 100, "max_output_tokens": 100, "input_digest": digest({})}
        result = (await control.request("execution-1", "invocation-grants", {"items": [plan]}))["items"][0]
        assert result["deny_reason"] == "SOURCE_NOT_PERMITTED"
    asyncio.run(run())


def test_command_duplicate_and_conflict_have_one_queue_effect():
    async def run():
        request = start()
        supervisor, control = setup(request)
        gate = asyncio.Event()
        async def stage(execution): await gate.wait()
        _, execution = await supervisor.accept(request, stage, control)
        payload = {"after_seq": 0}
        command = {"command_id": "command-1", "kind": "REDELIVER", "boot": request["boot"], "payload": payload, "payload_digest": digest(payload)}
        await supervisor.command(execution.id, command)
        await supervisor.command(execution.id, command)
        assert execution.command_queue.qsize() == 1
        command["payload"]["after_seq"] = 2
        command["payload_digest"] = digest(command["payload"])
        with pytest.raises(ContractError, match="IDEMPOTENCY_CONFLICT"):
            await supervisor.command(execution.id, command)
        gate.set()
        await supervisor.wait(execution.id)
        assert execution.status == "CLOSED", execution.error_code
    asyncio.run(run())


def test_clock_subtracts_union_only_when_no_external_work_is_running():
    now = [0.0]
    clock = AgentClock(lambda: now[0])
    clock.start_agent()
    first = clock.measure("java")
    first.__enter__()
    now[0] = 1
    second = clock.measure("java")
    second.__enter__()
    now[0] = 2
    model = clock.measure("model")
    model.__enter__()
    now[0] = 4
    first.__exit__(None, None, None)
    now[0] = 5
    model.__exit__(None, None, None)
    now[0] = 6
    second.__exit__(None, None, None)
    now[0] = 10
    result = clock.finish_agent()
    assert result["agent_wall_ms"] == 10000
    assert result["java_blocked_ms"] == 3000
    assert result["agent_wait_ms"] == 7000


def test_scheduler_resource_priority_background_cap_and_cleanup():
    async def run():
        scheduler = BoundedScheduler({"model": 2, "read": 2}, background_model_limit=1, per_host_limit=1)
        gate, entered = asyncio.Event(), asyncio.Event()
        order = []
        async def first():
            entered.set()
            await gate.wait()
        async def job(name):
            order.append(name)
        one = asyncio.create_task(scheduler.run("model", first, background=True))
        await entered.wait()
        low = asyncio.create_task(scheduler.run("model", lambda: job("low"), priority=5, background=True))
        high = asyncio.create_task(scheduler.run("model", lambda: job("high"), priority=0, background=True))
        foreground = asyncio.create_task(scheduler.run("model", lambda: job("foreground")))
        await foreground
        assert order == ["foreground"]
        gate.set()
        await asyncio.gather(one, low, high)
        assert order == ["foreground", "high", "low"]
        await scheduler.close()
        assert not scheduler.running and not scheduler.pending and not any(scheduler.counts.values())
    asyncio.run(run())


def test_scheduler_same_host_and_global_limits_are_enforced():
    async def run():
        scheduler = BoundedScheduler({"read": 2}, background_model_limit=1, per_host_limit=1)
        gate = asyncio.Event()
        started = asyncio.Queue()
        async def read(name):
            await started.put(name)
            await gate.wait()
            return name
        tasks = [asyncio.create_task(scheduler.run("read", lambda name=name: read(name), host=host))
                 for name, host in [("a1", "a"), ("a2", "a"), ("b1", "b")]]
        first = {await started.get(), await started.get()}
        assert first == {"a1", "b1"}
        assert scheduler.counts["read"] == 2 and scheduler.hosts == {"a": 1, "b": 1}
        gate.set()
        assert await asyncio.gather(*tasks) == ["a1", "a2", "b1"]
        await scheduler.close()
    asyncio.run(run())


def test_immediate_cancel_and_duplicate_cancel_release_once():
    async def run():
        request = start()
        supervisor, control = setup(request)
        async def stage(execution): await asyncio.Event().wait()
        _, execution = await supervisor.accept(request, stage, control)
        views = await asyncio.gather(
            supervisor.cancel(execution.id, request["boot"], "USER_CANCELLED"),
            supervisor.cancel(execution.id, request["boot"], "USER_CANCELLED"),
        )
        assert all(view["slot_released"] for view in views)
        assert supervisor.active is None
        assert sum(event["kind"] == "CANCEL_REQUESTED" for event in execution.events) == 1
    asyncio.run(run())


def test_concurrent_grants_are_atomic_and_late_usage_cannot_publish():
    async def run():
        request = start()
        _, control = setup(request)
        round_budget = control.rounds[request["budget"]["round_budget_scope_id"]]
        round_budget.lanes["FOREGROUND"].cap = 200
        plan = {"invocation_id": "execution-1:inv:1", "attempt": 1, "purpose": "SCREEN", "cost_class": "MODEL", "lane": "FOREGROUND", "run_id": "run-1", "candidate_id": None, "read_ids": [], "model_binding_ref": "screen", "max_input_tokens": 100, "max_output_tokens": 100, "input_digest": digest({})}
        other = {**plan, "invocation_id": "execution-1:inv:2"}
        replies = await asyncio.gather(*[control.request("execution-1", "invocation-grants", {"items": [item]}) for item in (plan, other)])
        decisions = [reply["items"][0]["decision"] for reply in replies]
        assert decisions == ["GRANTED", "DENIED"]
        control.close_execution("execution-1")
        receipt = {"invocation_id": plan["invocation_id"], "receipt_id": "late", "outcome": "COMPLETED", "finish": "COMPLETE", "input_tokens": 4, "cached_input_tokens": 0, "output_tokens": 6, "provider_request_ref": "provider-1"}
        settlement = await control.request("execution-1", "usage-receipt", receipt)
        validate_definition("UsageSettlement", settlement)
        assert settlement["lanes"]["FOREGROUND"]["settled_microusd"] == 10
        with pytest.raises(ContractError, match="EXECUTION_GONE"):
            await control.request("execution-1", "delivery", {})
    asyncio.run(run())
