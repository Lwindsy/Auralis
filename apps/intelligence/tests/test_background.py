"""Round commands, continuation and interruption against the authoritative double."""

import asyncio
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from fractions import Fraction
from uuid import uuid4

import pytest
from auralis_intelligence.agents import CallResult, Execution, RoundSupervisor, Usage
from auralis_intelligence.agents.background import RoundPipeline
from auralis_intelligence.agents.demo import DemoHarness, _reply
from auralis_intelligence.contracts import (
    ContractError,
    content_digest,
    digest,
    validate_definition,
)


def make(tmp_path, *, r=6, k=3, n=1, idle=80, watermark=1):
    harness = DemoHarness(r=r, k=k, n=n, root=tmp_path)
    harness.request["deadlines"]["idle_close_ms"] = idle
    harness.request["agent_policy"]["supply"]["replenish_watermark"] = watermark
    harness.request["agent_policy"]["digest"] = content_digest(
        harness.request["agent_policy"], "digest"
    )
    harness.request["payload_digest"] = content_digest(harness.request)
    return harness


async def start(harness, request=None, supervisor=None):
    request = request or harness.request
    supervisor = supervisor or harness.supervisor
    base = harness.pipeline(request)
    pipeline = RoundPipeline(
        base.preferences, base.recall, base.research, base.writing, base.selection
    )
    harness.control.register(request)
    _, execution = await supervisor.accept(request, pipeline, harness.control)
    return execution, pipeline


async def until(condition):
    async def poll():
        while not condition():
            await asyncio.sleep(0.002)

    await asyncio.wait_for(poll(), 5)


async def command(harness, execution, kind, payload, *, identifier=None, publish_count=None):
    value = {
        "command_id": identifier or uuid4().hex,
        "kind": kind,
        "boot": execution.start["boot"],
        "payload": payload,
        "payload_digest": digest(payload),
    }
    harness.control.issue_command(execution.id, value, publish_count=publish_count)
    await harness.supervisor.command(execution.id, value)
    return value


def test_idle_close_keeps_remaining_candidates_and_round_report(tmp_path):
    async def run():
        harness = make(tmp_path)
        try:
            execution, pipeline = await start(harness)
            await harness.supervisor.wait(execution.id)
            assert execution.status == "CLOSED" and execution.slot_released
            assert execution.final_report["close_reason"] == "IDLE"
            assert execution.final_report["counts"]["unresearched"] == 0
            reports = [
                item
                for item in harness.control.deliveries.values()
                if item["kind"] == "ROUND_REPORT"
            ]
            assert len(reports) == 1
            validate_definition("RoundReport", reports[0]["payload"])
            import gc
            import weakref

            reference = weakref.ref(pipeline)
            del pipeline
            gc.collect()
            assert reference() is None
            assert execution.report_builder is execution.policy_handler is None
        finally:
            harness.close()

    asyncio.run(run())


def test_display_demand_supplies_once_excludes_shown_and_publishes_one_slot(tmp_path):
    async def run():
        harness = make(tmp_path, idle=500)
        try:
            execution, _ = await start(harness)
            await until(
                lambda: (
                    execution.work is not None
                    and execution.work.first_payload is not None
                    and not execution.foreground
                )
            )
            entry = harness.control.executions[execution.id]
            existing = list(execution.work.cards)
            payload = {
                "display_seq": entry["display"]["display_seq"] + 1,
                "shown_candidate_ids": existing,
                "heard_candidate_ids": [],
                "ready_unshown_ids": [],
                "user_waiting": True,
                "run_id": "supply-run-2",
            }
            sent = await command(
                harness,
                execution,
                "DISPLAY_STATE",
                payload,
                identifier="demand-once",
                publish_count=1,
            )
            await harness.supervisor.command(execution.id, sent)
            await harness.supervisor.wait(execution.id)
            supply = [
                item
                for item in harness.control.deliveries.values()
                if item["kind"] == "CARD_BATCH" and item["payload"]["batch_kind"] == "SUPPLY"
            ]
            assert len(supply) == 1 and supply[0]["run_id"] == "supply-run-2"
            assert not {card["candidate_id"] for card in supply[0]["payload"]["cards"]} & set(
                existing
            )
            assert len(entry["display"]["shown_candidate_ids"]) == len(existing) + 1
            assert any(
                item["plan"]["run_id"] == "supply-run-2"
                for item in harness.control.invocations.values()
            )
        finally:
            harness.close()

    asyncio.run(run())


def test_low_scores_do_not_fill_supply_and_exhaustion_does_not_recall_again(tmp_path):
    async def run():
        harness = make(tmp_path, r=4, k=1, n=1)
        try:
            screens = 0

            def reply(binding, request):
                nonlocal screens
                result = _reply(binding, request)
                if request.schema_name == "research_v2" and result.output["action"] == "finish":
                    screens += 1
                    result.output["assessment"]["score"] = 4 if screens == 1 else 2
                return result

            harness.adapter.replies = reply
            execution, _ = await start(harness)
            await harness.supervisor.wait(execution.id)
            assert len(harness.control.cards) == 1
            assert execution.final_report["counts"]["below_floor"] == 3
            assert execution.final_report["counts"]["researched"] == 4
            assert any(
                item["kind"] == "SUPPLY_STATUS" and item["payload"]["state"] == "R_EXHAUSTED"
                for item in harness.control.deliveries.values()
            )
            assert (
                sum(operation == "candidate-validation" for _, operation in harness.control.events)
                == 1
            )
        finally:
            harness.close()

    asyncio.run(run())


@pytest.mark.parametrize("reason", ["PREEMPTED", "SUPERSEDED", "USER_CANCELLED"])
def test_cancellation_preserves_reason_and_waits_for_physical_stop(reason, tmp_path):
    async def run():
        harness = make(tmp_path, idle=1000)
        try:
            harness.control.price_per_token = Fraction(1, 100)
            stopping, release = asyncio.Event(), asyncio.Event()
            original = harness.adapter.complete

            async def delayed(binding, request):
                if request.schema_name.startswith("long_review_v1"):
                    try:
                        await asyncio.Event().wait()
                    except asyncio.CancelledError:
                        stopping.set()
                        await release.wait()
                        raise
                return await original(binding, request)

            harness.adapter.complete = delayed
            execution, _ = await start(harness)
            await until(
                lambda: (
                    execution.work is not None
                    and execution.work.first_payload is not None
                    and not execution.foreground
                )
            )
            cancellation = asyncio.create_task(
                harness.supervisor.cancel(execution.id, execution.start["boot"], reason)
            )
            await stopping.wait()
            assert not execution.slot_released
            another = deepcopy(harness.request)
            another["execution_id"] = "another-execution"
            another["payload_digest"] = content_digest(another)
            with pytest.raises(ContractError, match="EXECUTOR_BUSY"):
                await harness.supervisor.accept(another, harness.pipeline(another), harness.control)
            release.set()
            result = await cancellation
            assert result["slot_released"] and result["status"] == "CANCELLED"
            assert execution.final_report["trigger_reason"] == reason
            assert execution.final_report["lanes"]["BACKGROUND"]["reserved_microusd"] > 0
            assert execution.final_report["input_tokens"] is None
        finally:
            harness.close()

    asyncio.run(run())


def test_maintenance_close_drains_started_work_without_new_calls(tmp_path):
    async def run():
        harness = make(tmp_path, idle=1000)
        try:
            release = asyncio.Event()
            original = harness.adapter.complete

            async def delayed(binding, request):
                if request.schema_name.startswith("long_review_v1"):
                    await release.wait()
                return await original(binding, request)

            harness.adapter.complete = delayed
            execution, _ = await start(harness)
            await until(
                lambda: (
                    execution.work is not None
                    and execution.work.first_payload is not None
                    and not execution.foreground
                )
            )
            before = len(harness.control.invocations)
            await command(harness, execution, "CLOSE", {"reason": "MAINTENANCE"})
            release.set()
            await harness.supervisor.wait(execution.id)
            assert len(harness.control.invocations) == before
            assert execution.final_report["close_reason"] == "CLOSE_COMMAND"
            assert execution.final_report["trigger_reason"] == "MAINTENANCE"
            assert execution.slot_released
        finally:
            harness.close()

    asyncio.run(run())


def test_continuation_rescreens_summaries_without_reads_or_recall(tmp_path):
    async def run():
        harness = make(tmp_path)
        try:
            first, _ = await start(harness)
            await harness.supervisor.wait(first.id)
            old_reads = len(harness.control.read_claims)
            old_calls = len(harness.adapter.requests)
            request = harness.control.continuation(
                first.id,
                new_execution_id="continued",
                python_boot_id="new-python",
                run_id="rescreen-2",
                rescreen=True,
            )
            harness.supervisor = RoundSupervisor(boot_id="new-python")
            continued, pipeline = await start(harness, request)
            await harness.supervisor.wait(continued.id)
            assert continued.status == "CLOSED", continued.error_code
            assert len(harness.control.read_claims) == old_reads and pipeline.recall.engine is None
            requests = [request for _, request in harness.adapter.requests[old_calls:]]
            assert len(requests) == 1 and requests[0].schema_name == "select_v1"
            assert requests[0].input["request_text"] is None
            assert any(
                "安静" in item.get("description", "") for item in requests[0].input["preferences"]
            )
            assert continued.final_report["input_tokens"] > first.final_report["input_tokens"]
        finally:
            harness.close()

    asyncio.run(run())


def test_interrupted_long_is_not_automatic_but_explicit_regeneration_rereads(tmp_path):
    async def run():
        harness = make(tmp_path, idle=1000)
        try:
            original = harness.adapter.complete

            async def delayed(binding, request):
                if request.schema_name.startswith("long_review_v1"):
                    await asyncio.Event().wait()
                return await original(binding, request)

            harness.adapter.complete = delayed
            first, _ = await start(harness)
            await until(
                lambda: (
                    first.work is not None
                    and first.work.first_payload is not None
                    and not first.foreground
                )
            )
            target = next(iter(first.work.shown))
            await harness.supervisor.cancel(first.id, first.start["boot"], "PREEMPTED")
            harness.control.close_execution(first.id)
            harness.adapter.complete = original
            before_long = sum(
                item["plan"]["purpose"] == "WRITE_LONG"
                for item in harness.control.invocations.values()
            )
            resume = harness.control.continuation(
                first.id,
                new_execution_id="resume-no-regeneration",
                python_boot_id="new-python",
                run_id="rescreen-new",
                rescreen=True,
            )
            resume["deadlines"]["idle_close_ms"] = 30
            resume["payload_digest"] = content_digest(resume)
            harness.supervisor = RoundSupervisor(boot_id="new-python")
            second, _ = await start(harness, resume)
            await harness.supervisor.wait(second.id)
            assert (
                sum(
                    item["plan"]["purpose"] == "WRITE_LONG"
                    for item in harness.control.invocations.values()
                )
                == before_long
            )
            before_reads = len(harness.control.reads)
            explicit = harness.control.continuation(
                second.id,
                new_execution_id="resume-regenerate",
                python_boot_id="third-python",
                regenerate=(target,),
            )
            harness.supervisor = RoundSupervisor(boot_id="third-python")
            third, _ = await start(harness, explicit)
            await harness.supervisor.wait(third.id)
            assert third.status == "CLOSED", third.error_code
            assert len(harness.control.reads) >= before_reads + 2
            assert harness.control.long_states[(explicit["round_id"], target)] == "READY"
            fresh = [
                item for item in harness.control.reads.values() if item["execution_id"] == third.id
            ]
            assert fresh and all(item["receipt"]["purpose"] == "REREAD" for item in fresh)
        finally:
            harness.close()

    asyncio.run(run())


def test_java_restart_preserves_budget_and_old_execution_cannot_publish(tmp_path):
    async def run():
        harness = make(tmp_path)
        try:
            harness.control.price_per_token = Fraction(1, 100)
            first, _ = await start(harness)
            await harness.supervisor.wait(first.id)
            account = harness.control.sessions[harness.request["budget"]["session_budget_scope_id"]]
            spent = account.settled
            assert spent > 0
            harness.control.restart("restarted-java")
            request = harness.control.continuation(
                first.id,
                new_execution_id="after-java-restart",
                python_boot_id="new-python",
                run_id="rescreen-new",
                rescreen=True,
            )
            harness.supervisor = RoundSupervisor(boot_id="new-python")
            second, _ = await start(harness, request)
            await harness.supervisor.wait(second.id)
            assert (
                account.settled > spent
                and len(harness.control.sessions) == len(harness.control.rounds) == 1
            )
            with pytest.raises(ContractError, match="EXECUTION_GONE"):
                await harness.control.request(first.id, "delivery", {})
        finally:
            harness.close()

    asyncio.run(run())


def test_revocation_push_loss_rejects_late_long_but_settles_usage(tmp_path):
    async def run():
        harness = make(tmp_path, idle=1000)
        try:
            harness.control.price_per_token = Fraction(1, 100)
            release = asyncio.Event()
            original = harness.adapter.complete

            async def delayed(binding, request):
                if request.schema_name.startswith("long_review_v1"):
                    await release.wait()
                return await original(binding, request)

            harness.adapter.complete = delayed
            execution, _ = await start(harness)
            await until(
                lambda: (
                    execution.work is not None
                    and execution.work.first_payload is not None
                    and not execution.foreground
                )
            )
            account = harness.control.sessions[harness.request["budget"]["session_budget_scope_id"]]
            before = account.settled
            harness.control.policy["rules"][0]["decision"] = "DENY"
            harness.control.policy["policy_epoch"] += 1  # Deliberately omit the push.
            await command(harness, execution, "CLOSE", {"reason": "MAINTENANCE"})
            release.set()
            await harness.supervisor.wait(execution.id)
            assert account.settled > before
            assert not any(
                item["status"] == "READY" for item in harness.control.long_reviews.values()
            )
            rejected = [
                item
                for item in harness.control.deliveries.values()
                if item["kind"] == "LONG_REVIEW"
            ]
            assert rejected and all(item["payload"]["sections"] is None for item in rejected)
        finally:
            harness.close()

    asyncio.run(run())


def test_stale_continuation_binding_and_changed_budget_scope_are_rejected(tmp_path):
    async def run():
        harness = make(tmp_path)
        try:
            first, _ = await start(harness)
            await harness.supervisor.wait(first.id)
            request = harness.control.continuation(
                first.id, new_execution_id="tampered", python_boot_id="new-python"
            )
            request["continuation"]["candidate_bundle"]["binding"]["recall_binding"]["window"] = (
                "wrong-window"
            )
            request["payload_digest"] = content_digest(request)
            with pytest.raises(ContractError, match="BINDING_MISMATCH"):
                harness.control.register(request)
            request = harness.control.continuation(
                first.id, new_execution_id="budget-tampered", python_boot_id="new-python"
            )
            request["budget"]["round_budget_scope_id"] = "fresh-money"
            request["payload_digest"] = content_digest(request)
            with pytest.raises(ContractError, match="BINDING_MISMATCH"):
                harness.control.register(request)
        finally:
            harness.close()

    asyncio.run(run())


def test_displayed_long_priority_and_user_boost_reorder_only_pending_work(tmp_path):
    async def run():
        harness = make(tmp_path, r=3, k=3, n=1, idle=1000)
        try:
            harness.request["agent_policy"]["concurrency"].update(
                model_max_in_flight=2, background_model_max_in_flight=1
            )
            harness.request["agent_policy"]["digest"] = content_digest(
                harness.request["agent_policy"], "digest"
            )
            harness.request["payload_digest"] = content_digest(harness.request)
            order, release = [], asyncio.Event()
            original = harness.adapter.complete

            async def delayed(binding, request):
                if request.schema_name.startswith("long_review_v1"):
                    identifier = request.input["album"]["title"].replace("Synthetic Album ", "")
                    order.append(identifier)
                    if len(order) == 1:
                        await release.wait()
                return await original(binding, request)

            harness.adapter.complete = delayed
            execution, _ = await start(harness)
            await until(
                lambda: (
                    execution.work is not None
                    and execution.work.first_payload is not None
                    and not execution.foreground
                )
            )
            identifiers = list(execution.work.cards)
            queued = [identifier for identifier in identifiers if identifier != order[0]]
            display = harness.control.executions[execution.id]["display"]
            await command(
                harness,
                execution,
                "DISPLAY_STATE",
                {
                    "display_seq": display["display_seq"] + 1,
                    "shown_candidate_ids": identifiers,
                    "heard_candidate_ids": [],
                    "ready_unshown_ids": [],
                    "user_waiting": False,
                    "run_id": None,
                },
            )
            await command(
                harness, execution, "PRIORITIZE_LONG_REVIEW", {"candidate_id": queued[-1]}
            )
            await until(lambda: execution.priorities.get(queued[0]) == 2)
            assert execution.priorities[queued[-1]] == 1
            release.set()
            await harness.supervisor.wait(execution.id)
            assert order[1] == queued[-1]
        finally:
            harness.close()

    asyncio.run(run())


def test_background_exhaustion_waits_for_explicit_budget_and_regeneration(tmp_path):
    async def run():
        harness = make(tmp_path, idle=1000)
        try:
            harness.control.price_per_token = Fraction(1, 100)
            harness.request["budget"]["limits"]["lane_caps"] = {
                "FOREGROUND": 59999,
                "BACKGROUND": 1,
            }
            harness.request["payload_digest"] = content_digest(harness.request)
            execution, _ = await start(harness)
            await until(
                lambda: any(
                    state == "BUDGET_EXHAUSTED" for state in harness.control.long_states.values()
                )
            )
            target = harness.control.executions[execution.id]["display"]["shown_candidate_ids"][0]
            before = len(
                [
                    request
                    for _, request in harness.adapter.requests
                    if request.schema_name.startswith("long_review_v1")
                ]
            )
            assert before == 0
            update = await command(
                harness,
                execution,
                "BUDGET_UPDATE",
                {"FOREGROUND": 0, "BACKGROUND": 10000},
                identifier="money-once",
            )
            harness.control.issue_command(execution.id, update)
            await harness.supervisor.command(execution.id, update)
            await command(harness, execution, "REGENERATE_LONG_REVIEW", {"candidate_id": target})
            await until(
                lambda: (
                    harness.control.long_states[(execution.start["round_id"], target)] == "READY"
                )
            )
            await command(harness, execution, "CLOSE", {"reason": "TEST_COMPLETE"})
            await harness.supervisor.wait(execution.id)
            budget = harness.control.rounds[harness.request["budget"]["round_budget_scope_id"]]
            assert budget.lanes["BACKGROUND"].cap == 10001
            assert sum(account.cap for account in budget.lanes.values()) == 60000
            assert (
                len(
                    [
                        request
                        for _, request in harness.adapter.requests
                        if request.schema_name.startswith("long_review_v1")
                    ]
                )
                == 1
            )
        finally:
            harness.close()

    asyncio.run(run())


def test_background_repair_allowance_survives_continuation(tmp_path):
    async def run():
        harness = make(tmp_path)
        try:

            def bad_long(binding, request):
                result = _reply(binding, request)
                if request.schema_name.startswith("long_review_v1"):
                    result.output["sections"]["vocals"] = "伪造引用[R999]"
                return result

            harness.adapter.replies = bad_long
            first, _ = await start(harness)
            await harness.supervisor.wait(first.id)
            target = harness.control.executions[first.id]["display"]["shown_candidate_ids"][0]
            budget = harness.control.rounds[harness.request["budget"]["round_budget_scope_id"]]
            assert budget.repairs["BACKGROUND"] == 1
            request = harness.control.continuation(
                first.id,
                new_execution_id="repair-resume",
                python_boot_id="repair-python",
                regenerate=(target,),
            )
            harness.supervisor = RoundSupervisor(boot_id="repair-python")
            second, _ = await start(harness, request)
            await harness.supervisor.wait(second.id)
            assert budget.repairs["BACKGROUND"] == 1
            assert second.final_report["structure_repairs"] == 1
            assert harness.control.long_states[(request["round_id"], target)] == "FAILED"
        finally:
            harness.close()

    asyncio.run(run())


def test_stale_display_is_ignored_without_foreground_or_repeat_work(tmp_path):
    async def run():
        harness = make(tmp_path, idle=300)
        try:
            execution, _ = await start(harness)
            await until(
                lambda: (
                    execution.work is not None
                    and execution.work.first_payload is not None
                    and not execution.foreground
                )
            )
            before = deepcopy(harness.control.executions[execution.id]["display"])
            stale = {
                "display_seq": 0,
                "shown_candidate_ids": [],
                "heard_candidate_ids": [],
                "ready_unshown_ids": [],
                "user_waiting": True,
                "run_id": "stale-run",
            }
            await command(harness, execution, "DISPLAY_STATE", stale)
            assert not execution.foreground
            assert harness.control.executions[execution.id]["display"] == before
            await harness.supervisor.wait(execution.id)
            assert execution.final_report["close_reason"] == "IDLE"
        finally:
            harness.close()

    asyncio.run(run())


def test_expired_source_snapshot_prevents_resume_model_and_read_use(tmp_path):
    async def run():
        harness = make(tmp_path)
        try:
            first, _ = await start(harness)
            await harness.supervisor.wait(first.id)
            harness.control.policy["valid_until"] = "2000-01-01T00:00:00Z"
            before_calls, before_reads = len(harness.adapter.requests), len(harness.control.reads)
            request = harness.control.continuation(
                first.id,
                new_execution_id="expired-sources",
                python_boot_id="next-python",
                run_id="rescreen-expired",
                rescreen=True,
            )
            harness.supervisor = RoundSupervisor(boot_id="next-python")
            second, _ = await start(harness, request)
            await harness.supervisor.wait(second.id)
            assert (
                len(harness.adapter.requests) == before_calls
                and len(harness.control.reads) == before_reads
            )
            assert harness.control.executions[second.id]["display"]["ready_unshown_ids"] == []
        finally:
            harness.close()

    asyncio.run(run())


def test_round_deadline_releases_slot_and_records_lifetime_reason(tmp_path):
    async def run():
        harness = make(tmp_path, idle=10000)
        try:
            harness.request["deadlines"]["round_deadline_at"] = (
                (datetime.now(timezone.utc) + timedelta(seconds=0.4))
                .isoformat()
                .replace("+00:00", "Z")
            )
            harness.request["payload_digest"] = content_digest(harness.request)
            execution, _ = await start(harness)
            await asyncio.wait_for(harness.supervisor.wait(execution.id), 3)
            assert (
                execution.slot_released
                and execution.final_report["close_reason"] == "ROUND_MAX_LIFETIME"
            )
        finally:
            harness.close()

    asyncio.run(run())


def test_rescreen_deadline_is_bounded_and_reports_basic_timeout(tmp_path):
    async def run():
        harness = make(tmp_path, idle=500)
        try:
            execution, _ = await start(harness)
            await until(
                lambda: (
                    execution.work is not None
                    and execution.work.first_payload is not None
                    and not execution.foreground
                )
            )
            original = harness.adapter.complete

            async def delayed(binding, request):
                if request.schema_name == "select_v1":
                    await asyncio.Event().wait()
                return await original(binding, request)

            harness.adapter.complete = delayed
            await command(
                harness,
                execution,
                "RESCREEN",
                {
                    "run_id": "bounded-rescreen",
                    "deadline": (datetime.now(timezone.utc) + timedelta(seconds=0.05))
                    .isoformat()
                    .replace("+00:00", "Z"),
                },
            )
            await until(
                lambda: any(
                    item["kind"] == "ORDER_UPDATE"
                    and item["payload"]["run_id"] == "bounded-rescreen"
                    for item in harness.control.deliveries.values()
                )
            )
            update = next(
                item["payload"]
                for item in harness.control.deliveries.values()
                if item["kind"] == "ORDER_UPDATE"
                and item["payload"]["run_id"] == "bounded-rescreen"
            )
            assert (
                update["analysis_mode"] == "BASIC" and update["selection"]["status"] == "TIMED_OUT"
            )
            await command(harness, execution, "CLOSE", {"reason": "TEST_COMPLETE"})
            await harness.supervisor.wait(execution.id)
        finally:
            harness.close()

    asyncio.run(run())


def test_slow_usage_control_does_not_hold_physical_resources_forever(tmp_path):
    async def run():
        harness = make(tmp_path)
        try:
            harness.control.price_per_token = Fraction(1, 100)
            harness.control.register(harness.request)
            attempts, closed = [], []
            original = harness.control.request

            async def slow(identifier, operation, payload):
                if operation == "usage-receipt":
                    attempts.append(True)
                    try:
                        await asyncio.Event().wait()
                    finally:
                        closed.append(True)
                return await original(identifier, operation, payload)

            harness.control.request = slow
            execution = Execution(harness.request, harness.control, receipt_timeout_seconds=0.02)
            sent = []

            async def send():
                sent.append(True)
                return CallResult({"ok": True}, usage=Usage(1, 1, 0))

            plans = [
                execution.invocation_plan(
                    "SCREEN",
                    "screen",
                    {"number": index},
                    max_input_tokens=100,
                    max_output_tokens=100,
                )
                for index in range(5)
            ]
            await asyncio.wait_for(
                asyncio.gather(
                    *(execution.invoke(plan, send, timeout_seconds=1) for plan in plans)
                ),
                1,
            )
            await asyncio.wait_for(execution.cleanup(), 1)
            assert len(sent) == 5 and len(attempts) == len(closed) <= 2
            assert execution.slot_released and not execution.scheduler.running
            assert (
                harness.control.sessions[
                    harness.request["budget"]["session_budget_scope_id"]
                ].reserved
                == 10
            )
        finally:
            harness.close()

    asyncio.run(run())


def test_policy_push_purges_dossier_excerpts_before_late_output(tmp_path):
    async def run():
        harness = make(tmp_path, idle=1000)
        try:
            release = asyncio.Event()
            original = harness.adapter.complete

            async def delayed(binding, request):
                if request.schema_name.startswith("long_review_v1"):
                    await release.wait()
                return await original(binding, request)

            harness.adapter.complete = delayed
            execution, _ = await start(harness)
            await until(
                lambda: (
                    execution.work is not None
                    and execution.work.first_payload is not None
                    and not execution.foreground
                )
            )
            policy = deepcopy(harness.control.policy)
            policy["policy_epoch"] += 1
            policy["rules"][0]["decision"] = "DENY"
            await command(harness, execution, "SOURCE_POLICY_UPDATE", policy)
            assert all(
                not record.excerpt
                for dossier in execution.dossiers.values()
                for record in dossier.reads
                if record.result.receipt["publisher_key"] == "one.example"
            )
            release.set()
            await command(harness, execution, "CLOSE", {"reason": "TEST_COMPLETE"})
            await harness.supervisor.wait(execution.id)
            assert not any(
                item["status"] == "READY" for item in harness.control.long_reviews.values()
            )
        finally:
            harness.close()

    asyncio.run(run())


def test_unknown_usage_can_confirm_model_completion_without_releasing_reservation(tmp_path):
    async def run():
        harness = make(tmp_path)
        try:
            harness.control.price_per_token = Fraction(1, 100)
            harness.control.register(harness.request)
            execution = Execution(harness.request, harness.control)

            async def send():
                return CallResult({"score": 4})

            result = await execution.invoke(
                execution.invocation_plan(
                    "SCREEN", "screen", {}, max_input_tokens=100, max_output_tokens=100
                ),
                send,
                timeout_seconds=1,
            )
            invoice = harness.control.invocations[result.invocation_id]
            assert invoice["outcome"] == "COMPLETED" and invoice["finish"] == "COMPLETE"
            assert not invoice["settled"] and invoice["uncertain"] and invoice["reserved"] == 2
            await execution.cleanup()
        finally:
            harness.close()

    asyncio.run(run())


def test_actual_http_background_command_and_erasure(tmp_path):
    import httpx
    from auralis_intelligence.agents.http import (
        ControlHTTPClient,
        create_control_double_app,
        create_execution_app,
    )
    from auralis_intelligence.contracts import envelope
    from test_first_batch import running

    async def run():
        harness = make(tmp_path, idle=1000)
        try:
            async with running(
                create_control_double_app(harness.control, token="control-test")
            ) as control_url:
                client = ControlHTTPClient(control_url, "control-test")
                try:

                    def factory(request):
                        return harness.pipeline(request, background=True)

                    app = create_execution_app(
                        harness.supervisor,
                        client,
                        factory,
                        token="service-test",
                        prepare=harness.control.register,
                    )
                    async with (
                        running(app) as url,
                        httpx.AsyncClient(
                            base_url=url,
                            trust_env=False,
                            headers={
                                "Authorization": "Bearer service-test",
                                "X-Auralis-Audience": "intelligence",
                            },
                        ) as http,
                    ):
                        path = "/internal/v1/executions/" + harness.request["execution_id"]
                        assert (
                            await http.put(
                                path, json=envelope("RoundStart", harness.request, "start-http")
                            )
                        ).status_code == 201
                        await until(
                            lambda: (
                                harness.supervisor.active is not None
                                and harness.supervisor.active.work is not None
                                and harness.supervisor.active.work.first_payload is not None
                                and not harness.supervisor.active.foreground
                            )
                        )
                        execution = harness.supervisor.active
                        payload = {"reason": "MAINTENANCE"}
                        value = {
                            "command_id": "close-http",
                            "kind": "CLOSE",
                            "boot": execution.start["boot"],
                            "payload": payload,
                            "payload_digest": digest(payload),
                        }
                        harness.control.issue_command(execution.id, value)
                        reply = await http.put(
                            path + "/commands/close-http",
                            json=envelope("RoundCommand", value, "command-http"),
                        )
                        assert reply.status_code == 202
                        await harness.supervisor.wait(execution.id)
                        assert execution.final_report["trigger_reason"] == "MAINTENANCE"
                        duplicate = await http.put(
                            path,
                            json=envelope("RoundStart", harness.request, "duplicate-completed"),
                        )
                        assert duplicate.status_code == 200
                        erase = await http.put(
                            "/internal/v1/erasures/" + harness.request["erasure_handle"],
                            json=envelope("Erasure", {"epoch": 1}, "erase-http"),
                        )
                        assert (
                            erase.status_code == 200 and (await http.get(path)).status_code == 410
                        )
                        assert (
                            await http.put(
                                path, json=envelope("RoundStart", harness.request, "old-start")
                            )
                        ).json()["payload"]["code"] == "SUBJECT_REVOKED"
                finally:
                    await client.close()
        finally:
            harness.close()

    asyncio.run(run())
