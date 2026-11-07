"""HTTP-independent execution, single-send calls, and acknowledged delivery."""

import asyncio
from copy import deepcopy
from datetime import datetime, timezone
from dataclasses import replace
import json
import time
from typing import Any, Awaitable, Callable
from uuid import uuid4

from auralis_intelligence.contracts import ContractError, IdempotencyLedger, digest, validate_definition
from .clock import AgentClock
from .ports import CallDenied, CallResult, ControlPort, ControlUnavailable, DeliveryPending, Stage
from .scheduler import BoundedScheduler


class Execution:
    def __init__(self, start: dict[str, Any], control: ControlPort, *, monotonic: Callable[[], float] = time.perf_counter,
                 buffer_items: int = 128, buffer_bytes: int = 4000000, receipt_timeout_seconds: float = 1.0) -> None:
        if not 0 < receipt_timeout_seconds <= 30:
            raise ValueError("INVALID_RECEIPT_TIMEOUT")
        self.receipt_timeout_seconds = receipt_timeout_seconds
        self._receipt_backoff_until = 0.0
        self.start = deepcopy(start)
        self.id = start["execution_id"]
        self.control = control
        self.clock = AgentClock(monotonic)
        self.created_at = self.clock.now()
        self.final_timing: dict[str, int] | None = None
        limits = start["agent_policy"]["concurrency"]
        self.scheduler = BoundedScheduler({"model": limits["model_max_in_flight"], "search": limits["search_max_in_flight"], "read": limits["read_max_in_flight"]}, background_model_limit=limits["background_model_max_in_flight"], per_host_limit=limits["read_max_per_host"])
        self.status = "ACCEPTED"
        self.phase = "INTERPRETING" if start["input"]["raw"] is not None else "RECALLING"
        self.foreground = start["foreground"]
        self.slot_released = False
        self.cancelled = asyncio.Event()
        self.started = asyncio.Event()
        self.close_requested = False
        self.close_reason: str | None = None
        self.error_code: str | None = None
        self.binding: dict[str, Any] | None = None
        self.candidates: dict[str, dict[str, Any]] = {}
        if start.get("curated_candidates") is not None:
            self.binding = deepcopy(start["curated_candidates"]["binding"])
            self.candidates = {item["release_group_id"]: deepcopy(item) for item in start["curated_candidates"]["accepted"]}
        self.dossiers: dict[str, Any] = {}
        self.buffer: dict[str, dict[str, Any]] = {}
        self.delivery_results: dict[str, dict[str, Any]] = {}
        self.handoff: str | None = None
        self.work: Any = None
        self.final_report: dict[str, Any] | None = None
        self.report_builder: Callable | None = None
        self.policy_handler: Callable | None = None
        self.first_outcome: str | None = None
        self.first_reason: str | None = None
        self.priorities: dict[str, int] = {}
        self.revoked_candidates: set[str] = set()
        self.buffer_items, self.buffer_bytes = buffer_items, buffer_bytes
        self.sequence = 0
        self.call_sequence = 0
        self.uncertain: set[str] = set()
        self.pending_usage: dict[str, dict[str, Any]] = {}
        self._settlement_seq = -1
        self._usage_lock = asyncio.Lock()
        self.commands = IdempotencyLedger()
        self.events: list[dict[str, Any]] = []
        self.command_queue: asyncio.Queue = asyncio.Queue(maxsize=128)
        self.updated_seq = 0
        self.counters = {name: 0 for name in ("researched", "qualified", "shortlisted", "ready", "long_ready", "long_generating")}
        self.lanes = None
        self.budget_state = None
        self._issued: set[str] = set()
        self.children: set[asyncio.Task] = set()
        self.calls: dict[str, dict[str, Any]] = {}
        self._delivery_lock = asyncio.Lock()
        self._deadline = datetime.fromisoformat(start["deadlines"]["round_deadline_at"].replace("Z", "+00:00"))

    def ensure_active(self, *, start_work: bool = True, allow_expired: bool = False) -> None:
        if self.cancelled.is_set():
            raise asyncio.CancelledError
        if self.slot_released or self.status in {"CLOSED", "FAILED", "CANCELLED"}:
            raise CallDenied("EXECUTION_CLOSED")
        if self.close_requested and start_work:
            raise CallDenied("EXECUTION_CLOSING")
        if not allow_expired and datetime.now(timezone.utc) >= self._deadline:
            raise CallDenied("DEADLINE_EXCEEDED")

    def event(self, kind: str, object_id: str | None = None) -> None:
        if kind not in {"ACCEPTED", "RUNNING", "CALL_GRANTED", "CALL_UNCERTAIN", "DELIVERED", "CANCEL_REQUESTED", "CLOSED", "FAILED", "COMMAND"}:
            raise ValueError("UNKNOWN_EVENT")
        if len(self.events) >= 4096:
            self.events.pop(0)
        self.updated_seq += 1
        self.events.append({"seq": self.updated_seq, "kind": kind, "object_id": object_id})

    def spawn(self, factory: Callable[[], Awaitable[Any]], *, start_work: bool = True) -> asyncio.Task:
        self.ensure_active(start_work=start_work)
        task = asyncio.create_task(factory())
        self.children.add(task)
        def completed(finished: asyncio.Task) -> None:
            self.children.discard(finished)
            if not finished.cancelled() and finished.exception() is not None:
                self.event("FAILED", "child-stage")
        task.add_done_callback(completed)
        return task

    def prioritize_long(self, candidate_id: str, priority: int = 1) -> None:
        self.priorities[candidate_id] = min(priority, self.priorities.get(candidate_id, priority))
        self.scheduler.prioritize(candidate_id, priority)

    def reject_reads(self, read_ids: set[str]) -> None:
        for identifier, dossier in self.dossiers.items():
            if hasattr(dossier, "discard_reads") and dossier.discard_reads(read_ids):
                self.revoked_candidates.add(identifier)
                self.scheduler.cancel_pending(identifier)

    async def refresh_budget(self) -> dict[str, Any]:
        result = await self.control_request("budget-state", {})
        validate_definition("BudgetState", result)
        if result["settlement_seq"] >= self._settlement_seq:
            self._settlement_seq = result["settlement_seq"]
            self.lanes = deepcopy(result["lanes"])
            self.budget_state = deepcopy(result)
        return result

    async def control_request(self, operation: str, payload: dict[str, Any]) -> dict[str, Any]:
        with self.clock.measure("java"):
            result = await self.control.request(self.id, operation, payload)
        if operation == "preference-resolution":
            validate_definition("EffectivePreferenceSnapshot", result)
            if any(result[key] != payload[key] for key in ("input_digest", "reference_revision", "parser_revision")):
                raise ContractError("BINDING_MISMATCH")
            self.start["input"]["accepted_preferences"] = deepcopy(result)
        if operation == "candidate-validation":
            validate_definition("CandidateBundle", result)
            expected = {"effective_preference_digest": payload["effective_preference_digest"], "exclusion_digest": self.start["input"]["exclusion_digest"],
                        "selection_config_revision": self.start["selection_config"]["revision"], "agent_policy_revision": self.start["agent_policy"]["revision"], "recall_binding": self.start["recall_binding"]}
            if any(result["binding"][key] != value for key, value in expected.items()) or result["digest"] != digest(result["accepted"]) or result["binding"]["candidate_bundle_digest"] != result["digest"]:
                raise ContractError("BINDING_MISMATCH")
            proposed = {item["release_group_id"]: item["recall_rank"] for item in payload["candidates"]}
            identifiers = [item["release_group_id"] for item in result["accepted"]]
            if len(identifiers) != len(set(identifiers)) or any(proposed.get(item["release_group_id"]) != item["recall_rank"] for item in result["accepted"]):
                raise ContractError("BINDING_MISMATCH")
            self.binding = deepcopy(result["binding"])
            self.candidates = {item["release_group_id"]: deepcopy(item) for item in result["accepted"]}
        return result

    def invocation_plan(self, purpose: str, role: str, payload: Any, *, candidate_id: str | None = None,
                        read_ids: tuple[str, ...] = (), lane: str = "FOREGROUND", attempt: int = 1,
                        max_input_tokens: int | None = None, max_output_tokens: int | None = None) -> dict[str, Any]:
        self.call_sequence += 1
        binding = self.start["model_bindings"][role]
        return {"invocation_id": f"{self.id}:inv:{self.call_sequence}", "attempt": attempt, "purpose": purpose,
                "cost_class": "TOOL_SERVICE" if purpose == "SEARCH" else "MODEL", "lane": lane,
                "run_id": self.start["run_id"] if lane == "FOREGROUND" else None, "candidate_id": candidate_id,
                "read_ids": list(read_ids), "model_binding_ref": binding["ref"],
                "max_input_tokens": max_input_tokens or binding["max_input_tokens"],
                "max_output_tokens": max_output_tokens or binding["max_output_tokens"], "input_digest": digest(payload)}

    async def invoke(self, plan: dict[str, Any], send: Callable[[], Awaitable[CallResult]], *, timeout_seconds: float,
                     priority: int = 0) -> CallResult:
        self.ensure_active()
        validate_definition("InvocationGrant", plan)
        if plan["invocation_id"] in self._issued:
            raise CallDenied("ALREADY_CLAIMED")
        # Reserve locally before any await: concurrent duplicate callers cannot both send.
        self._issued.add(plan["invocation_id"])
        call = {"purpose": plan["purpose"], "candidate_id": plan["candidate_id"], "lane": plan["lane"],
                "queued_at": self.clock.now(), "sent_at": None, "finished_at": None}
        self.calls[plan["invocation_id"]] = call
        resource = "search" if plan["cost_class"] == "TOOL_SERVICE" else "model"
        async def perform() -> CallResult:
            self.ensure_active()
            try:
                response = await self.control_request("invocation-grants", {"items": [plan]})
            except ControlUnavailable as error:
                self.lanes = self.budget_state = None
                self.uncertain.add(plan["invocation_id"])
                self.event("CALL_UNCERTAIN", plan["invocation_id"])
                raise CallDenied("GRANT_UNCERTAIN") from error
            validate_definition("GrantResults", response)
            result = response["items"][0]
            if result["invocation_id"] != plan["invocation_id"]:
                raise ContractError("BINDING_MISMATCH")
            if result["decision"] != "GRANTED":
                if result["not_permitted_read_ids"]:
                    self.reject_reads(set(result["not_permitted_read_ids"]))
                raise CallDenied(result["deny_reason"] or "ALREADY_CLAIMED")
            self.lanes = None
            self.budget_state = None
            self.event("CALL_GRANTED", plan["invocation_id"])
            reply = CallResult(outcome="NOT_PROCESSED", finish="ERROR")
            cancelled = False
            try:
                self.ensure_active()
                if plan["read_ids"] and result["policy_epoch"] < self.start["source_policy"]["policy_epoch"]:
                    raise CallDenied("STALE_SOURCE_POLICY")
                call["sent_at"] = self.clock.now()
                reply = CallResult(outcome="UNCERTAIN", finish="ERROR")
                with self.clock.measure(resource):
                    reply = await asyncio.wait_for(send(), timeout=timeout_seconds)
            except asyncio.CancelledError:
                cancelled = True
                reply = CallResult(outcome="CANCELLED_AFTER_SEND" if call["sent_at"] is not None else "NOT_PROCESSED", finish="ERROR")
            except (asyncio.TimeoutError, TimeoutError, OSError, ControlUnavailable):
                reply = CallResult(outcome="UNCERTAIN", finish="ERROR")
            finally:
                call["finished_at"] = self.clock.now()
                receipt = {"invocation_id": plan["invocation_id"], "receipt_id": plan["invocation_id"] + ":usage", "outcome": reply.outcome, "finish": reply.finish, "input_tokens": reply.usage.input_tokens, "output_tokens": reply.usage.output_tokens, "cached_input_tokens": reply.usage.cached_input_tokens, "provider_request_ref": reply.provider_request_ref}
                if reply.outcome != "NOT_PROCESSED" and (reply.outcome in {"UNCERTAIN", "CANCELLED_AFTER_SEND"} or reply.usage.input_tokens is None):
                    self.uncertain.add(plan["invocation_id"])
                self.pending_usage[plan["invocation_id"]] = receipt
                settlement = asyncio.create_task(self.flush_usage())
                try:
                    await asyncio.shield(settlement)
                except asyncio.CancelledError:
                    cancelled = True
                    await settlement
            if cancelled:
                raise asyncio.CancelledError
            self.ensure_active(start_work=False)
            return replace(reply, invocation_id=plan["invocation_id"])
        priority = min(priority, self.priorities.get(plan["candidate_id"], priority))
        return await self.scheduler.run(resource, perform, priority=priority, background=plan["lane"] == "BACKGROUND", key=plan["candidate_id"])

    async def flush_usage(self, *, force: bool = False) -> None:
        async with self._usage_lock:
            if not force and self.clock.now() < self._receipt_backoff_until:
                return
            await self._flush_usage_locked()

    async def _flush_usage_locked(self) -> None:
        deadline = self.clock.now() + self.receipt_timeout_seconds
        for identifier, receipt in list(self.pending_usage.items()):
            remaining = deadline - self.clock.now()
            if remaining <= 0:
                self._receipt_backoff_until = self.clock.now() + self.receipt_timeout_seconds
                break
            try:
                settlement = await asyncio.wait_for(self.control_request("usage-receipt", receipt), remaining)
                validate_definition("UsageSettlement", settlement)
            except (ControlUnavailable, ContractError, asyncio.TimeoutError):
                self._receipt_backoff_until = self.clock.now() + self.receipt_timeout_seconds
                break
            self.pending_usage.pop(identifier, None)
            if settlement["status"] == "SETTLED":
                self.uncertain.discard(identifier)
            if settlement["settlement_seq"] > self._settlement_seq:
                self._settlement_seq = settlement["settlement_seq"]
                self.lanes = deepcopy(settlement["lanes"])

    async def deliver(self, kind: str, payload: Any, *, run_id: str | None = None,
                      versions: dict[str, Any] | None = None) -> dict[str, Any]:
        self.ensure_active(start_work=False, allow_expired=kind == "ROUND_REPORT")
        if self.binding is None and not (kind == "ROUND_REPORT" or kind == "CARD_BATCH" and payload.get("outcome") != "DISPLAYABLE" and not payload.get("cards") and not payload.get("screenings")):
            raise ContractError("MISSING_BINDING")
        async with self._delivery_lock:
            self.sequence += 1
            identifier = f"{self.id}:{self.sequence}"
            delivery = {"delivery_id": identifier, "kind": kind, "round_id": self.start["round_id"], "run_id": run_id,
                        "binding": deepcopy(self.binding), "versions": versions or {"prompt_versions": [], "model_binding_refs": []},
                        "payload": deepcopy(payload), "payload_digest": digest(payload)}
            validate_definition("Delivery", delivery)
            current_bytes = sum(len(json.dumps(item, ensure_ascii=False).encode("utf-8")) for item in self.buffer.values())
            size = len(json.dumps(delivery, ensure_ascii=False).encode("utf-8"))
            if len(self.buffer) >= self.buffer_items or current_bytes + size > self.buffer_bytes:
                raise CallDenied("DELIVERY_BUFFER_FULL")
            self.buffer[identifier] = delivery
        try:
            return await self._send_delivery(identifier)
        except ControlUnavailable as error:
            raise DeliveryPending(identifier) from error

    async def _send_delivery(self, identifier: str) -> dict[str, Any]:
        self.ensure_active(start_work=False, allow_expired=self.buffer.get(identifier, {}).get("kind") == "ROUND_REPORT")
        if identifier in self.delivery_results:
            return deepcopy(self.delivery_results[identifier])
        try:
            result = await self.control_request("delivery", self.buffer[identifier])
        except ContractError:
            self.buffer.pop(identifier, None)
            raise
        validate_definition("DeliveryResult", result)
        self.buffer.pop(identifier, None)
        self.delivery_results[identifier] = deepcopy(result)
        self.event("DELIVERED", identifier)
        return result

    async def redeliver(self, after_seq: int = 0) -> list[dict[str, Any]]:
        return [await self._send_delivery(identifier) for identifier in list(self.buffer) if int(identifier.rsplit(":", 1)[1]) > after_seq]

    def view(self) -> dict[str, Any]:
        result = {"execution_id": self.id, "round_id": self.start["round_id"], "boot_id": self.start["boot"]["python_boot_id"], "status": self.status, "phase": self.phase, "foreground": self.foreground, "slot_released": self.slot_released, "last_delivery_seq": self.sequence, "undelivered_count": len(self.buffer), "counters": dict(self.counters), "uncertain_invocations": sorted(self.uncertain), "lanes": deepcopy(self.lanes), "policy_epoch": self.start["source_policy"]["policy_epoch"], "valid_until": self.start["source_policy"]["valid_until"], "updated_seq": self.updated_seq}
        validate_definition("ExecutionView", result)
        return result

    async def cleanup(self) -> None:
        self.close_requested = True
        children = [task for task in self.children if task is not asyncio.current_task()]
        for task in children:
            task.cancel()
        if children:
            await asyncio.gather(*children, return_exceptions=True)
        await self.scheduler.close()
        await self.flush_usage(force=True)
        if self.report_builder is not None:
            try:
                await asyncio.wait_for(self.refresh_budget(), self.receipt_timeout_seconds)
            except (ControlUnavailable, ContractError, asyncio.TimeoutError):
                self.lanes = self.budget_state = None
            self.final_report = self.report_builder(self)
        self.report_builder = None
        self.policy_handler = None
        self.start["input"]["raw"] = None
        self.start["input"]["accepted_preferences"] = None
        self.start["continuation"] = None
        self.start["curated_candidates"] = None
        for dossier in self.dossiers.values():
            dossier.clear()
        self.dossiers.clear()
        self.candidates.clear()
        if self.work is not None:
            self.work.clear()
            self.work = None
        self.buffer.clear()
        self.delivery_results.clear()
        self.pending_usage.clear()
        self.commands.clear()
        while not self.command_queue.empty():
            self.command_queue.get_nowait()
        self._issued.clear()
        self.calls.clear()
        self.priorities.clear()
        self.revoked_candidates.clear()
        self.final_timing = self.clock.snapshot()
        self.clock.clear()
        self.foreground = False
        self.slot_released = True


class RoundSupervisor:
    def __init__(self, *, boot_id: str | None = None, max_records: int = 256, receipt_timeout_seconds: float = 1.0) -> None:
        self.boot_id = boot_id or str(uuid4())
        self.max_records = max_records
        self.receipt_timeout_seconds = receipt_timeout_seconds
        self.active: Execution | None = None
        self.records: dict[str, tuple[str, Execution, asyncio.Task]] = {}
        self.maintenance = False
        self._lock = asyncio.Lock()

    async def accept(self, start: dict[str, Any], stage: Stage, control: ControlPort) -> tuple[int, Execution]:
        validate_definition("RoundStart", start)
        if datetime.fromisoformat(start["deadlines"]["round_deadline_at"].replace("Z", "+00:00")) <= datetime.now(timezone.utc):
            raise ContractError("DEADLINE_EXCEEDED")
        if start["boot"]["python_boot_id"] != self.boot_id:
            raise ContractError("STALE_BOOT")
        async with self._lock:
            previous = self.records.get(start["execution_id"])
            if previous:
                if previous[0] != start["payload_digest"]:
                    raise ContractError("IDEMPOTENCY_CONFLICT")
                return 200, previous[1]
            if self.maintenance:
                raise ContractError("MAINTENANCE")
            if self.active is not None:
                raise ContractError("EXECUTOR_BUSY")
            now = datetime.now(timezone.utc)
            self.records = {key: value for key, value in self.records.items() if not value[1].slot_released or value[1]._deadline > now}
            if len(self.records) >= self.max_records:
                raise CallDenied("EXECUTION_RECORD_CAPACITY")
            execution = Execution(start, control, receipt_timeout_seconds=self.receipt_timeout_seconds)
            self.active = execution
            execution.event("ACCEPTED")
            task = asyncio.create_task(self._drive(execution, stage))
            self.records[execution.id] = (start["payload_digest"], execution, task)
            return 201, execution

    async def _drive(self, execution: Execution, stage: Stage) -> None:
        execution.started.set()
        execution.status = "RUNNING"
        execution.event("RUNNING")
        try:
            execution.ensure_active()
            remaining = max(0, (execution._deadline - datetime.now(timezone.utc)).total_seconds())
            await asyncio.wait_for(stage(execution), timeout=remaining)
            execution.status = "CLOSED" if not execution.cancelled.is_set() else "CANCELLED"
        except asyncio.CancelledError:
            execution.status = "CANCELLED"
        except asyncio.TimeoutError:
            execution.status = "CLOSED"
            execution.close_reason = "ROUND_MAX_LIFETIME"
            execution.error_code = "DEADLINE_EXCEEDED"
        except (ContractError, CallDenied) as error:
            execution.status = "FAILED"
            execution.error_code = str(error)
            execution.event("FAILED")
        except Exception:
            execution.status = "FAILED"
            execution.error_code = execution.error_code or "STAGE_FAILED"
            execution.event("FAILED")
        finally:
            await execution.cleanup()
            execution.event("CLOSED")
            async with self._lock:
                if self.active is execution:
                    self.active = None

    async def cancel(self, execution_id: str, boot: dict[str, str], reason: str) -> dict[str, Any]:
        validate_definition("Cancellation", {"boot": boot, "reason": reason})
        record = self.records.get(execution_id)
        if record is None:
            raise ContractError("EXECUTION_GONE")
        _, execution, task = record
        if boot != execution.start["boot"]:
            raise ContractError("STALE_BOOT")
        if not task.done():
            first_request = not execution.cancelled.is_set()
            if first_request:
                execution.cancelled.set()
                execution.close_reason = reason
                execution.status = "CLOSING"
                execution.event("CANCEL_REQUESTED")
                await execution.started.wait()
                if not task.done():
                    task.cancel()
            await asyncio.shield(task)
        return execution.view()

    async def command(self, execution_id: str, command: dict[str, Any]) -> dict[str, Any]:
        validate_definition("RoundCommand", command)
        record = self.records.get(execution_id)
        if record is None or record[1].slot_released:
            raise ContractError("EXECUTION_GONE")
        execution = record[1]
        if execution.cancelled.is_set() or execution.close_requested:
            raise ContractError("EXECUTION_GONE")
        if command["boot"] != execution.start["boot"]:
            raise ContractError("STALE_BOOT")
        found, result = execution.commands.lookup(command["command_id"], command)
        if found:
            return result
        kind, payload = command["kind"], command["payload"]
        if kind == "DISPLAY_STATE" and execution.work is not None and payload["display_seq"] <= execution.work.display_seq:
            result = {"accepted": True}
            execution.commands.record(command["command_id"], command, result)
            return result
        if kind == "RESCREEN" and datetime.fromisoformat(payload["deadline"].replace("Z", "+00:00")) <= datetime.now(timezone.utc):
            raise ContractError("DEADLINE_EXCEEDED")
        if kind == "RESCREEN" and execution.start["operation"] == "CURATED_REVIEWS":
            raise ContractError("UNKNOWN_CAPABILITY")
        if (kind == "RESCREEN" or kind == "DISPLAY_STATE" and payload["user_waiting"]) and execution.foreground:
            raise ContractError("EXECUTOR_BUSY")
        if kind in {"PRIORITIZE_LONG_REVIEW", "REGENERATE_LONG_REVIEW"} and payload["candidate_id"] not in execution.candidates:
            raise ContractError("BINDING_MISMATCH")
        if execution.command_queue.full():
            raise CallDenied("COMMAND_CAPACITY")
        result = {"accepted": True}
        if kind == "REDELIVER":
            execution.spawn(lambda: execution.redeliver(payload["after_seq"]))
        else:
            execution.command_queue.put_nowait(deepcopy(command))
        if kind == "CLOSE":
            execution.close_requested = True
            execution.close_reason = payload["reason"]
            execution.status = "CLOSING"
        elif kind == "PRIORITIZE_LONG_REVIEW":
            execution.prioritize_long(payload["candidate_id"])
        elif kind == "SOURCE_POLICY_UPDATE" and payload["policy_epoch"] > execution.start["source_policy"]["policy_epoch"]:
            execution.start["source_policy"] = deepcopy(payload)
            if execution.policy_handler:
                execution.policy_handler(execution, payload)
        elif kind == "RESCREEN" or kind == "DISPLAY_STATE" and payload["user_waiting"]:
            execution.foreground = True
        execution.commands.record(command["command_id"], command, result)
        execution.event("COMMAND", command["command_id"])
        return result

    async def wait(self, execution_id: str) -> Execution:
        _, execution, task = self.records[execution_id]
        await asyncio.shield(task)
        return execution
