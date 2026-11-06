"""In-process Java test double. Never use this as product authorization."""

import asyncio
from copy import deepcopy
from dataclasses import dataclass, field
from datetime import datetime, timezone
import re
from typing import Any, Callable
from urllib.parse import urlsplit

from auralis_intelligence.contracts import (
    ContractError, IdempotencyLedger, content_digest, digest, require_binding,
    validate_definition,
)
from .ports import ControlUnavailable


@dataclass
class BudgetAccount:
    cap: int
    reserved: int = 0
    settled: int = 0

    @property
    def remaining(self) -> int:
        return self.cap - self.reserved - self.settled


@dataclass
class RoundBudget:
    session_id: str
    total: BudgetAccount
    lanes: dict[str, BudgetAccount]
    calls: dict[str, int] = field(default_factory=lambda: {"MODEL": 0, "TOOL_SERVICE": 0, "READ": 0})
    repairs: dict[str, int] = field(default_factory=dict)


class JavaControlDouble:
    """Atomic in one event loop; explicitly synthetic prices and catalog."""

    def __init__(self, coordinator_boot_id: str, *, catalog: dict[str, dict[str, Any]] | None = None,
                 price_per_token: int = 1, now: Callable[[], datetime] | None = None) -> None:
        self.boot_id = coordinator_boot_id
        self.catalog = deepcopy(catalog or {})
        self.price_per_token = price_per_token
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.sessions: dict[str, BudgetAccount] = {}
        self.rounds: dict[str, RoundBudget] = {}
        self.executions: dict[str, dict[str, Any]] = {}
        self.invocations: dict[str, dict[str, Any]] = {}
        self.reads: dict[str, dict[str, Any]] = {}
        self.read_claims: set[tuple[str, str, str]] = set()
        self.deliveries: dict[str, dict[str, Any]] = {}
        self.screenings: dict[tuple[str, str], dict[str, Any]] = {}
        self.ledger = IdempotencyLedger(20000)
        self.policy: dict[str, Any] | None = None
        self.unavailable: set[str] = set()
        self.lose_response_once: set[str] = set()
        self.events: list[tuple[str, str]] = []
        self._lock = asyncio.Lock()
        self.price_violation = False
        self._settlement_seq = 0
        self.preference_resolver: Callable[[dict[str, Any], dict[str, Any]], dict[str, Any]] | None = None

    def register(self, start: dict[str, Any]) -> None:
        validate_definition("RoundStart", start)
        if start["boot"]["coordinator_boot_id"] != self.boot_id:
            raise ContractError("STALE_BOOT")
        identifier = start["execution_id"]
        if identifier in self.executions:
            require_binding(self.executions[identifier]["start"], start)
            return
        budget = start["budget"]
        session_id, round_id = budget["session_budget_scope_id"], budget["round_budget_scope_id"]
        limits = budget["limits"]
        if session_id in self.sessions and self.sessions[session_id].cap != limits["session_cap_microusd"]:
            raise ContractError("BINDING_MISMATCH")
        self.sessions.setdefault(session_id, BudgetAccount(limits["session_cap_microusd"]))
        if round_id in self.rounds:
            previous = self.rounds[round_id]
            if previous.session_id != session_id or previous.total.cap != limits["round_cap_microusd"]:
                raise ContractError("BINDING_MISMATCH")
        else:
            self.rounds[round_id] = RoundBudget(session_id, BudgetAccount(limits["round_cap_microusd"]), {name: BudgetAccount(cap) for name, cap in limits["lane_caps"].items()})
        self.executions[identifier] = {"start": deepcopy(start), "active": True, "bundle": None, "display": {"display_seq": 0, "shown_candidate_ids": [], "heard_candidate_ids": [], "ready_unshown_ids": [], "user_waiting": False}}
        if self.policy is None or start["source_policy"]["policy_epoch"] > self.policy["policy_epoch"]:
            self.policy = deepcopy(start["source_policy"])

    def close_execution(self, execution_id: str) -> None:
        entry = self.executions[execution_id]
        entry["active"] = False
        entry["start"]["input"]["raw"] = None

    def permits(self, url: str, use: str) -> bool:
        policy = self.policy
        if policy is None or datetime.fromisoformat(policy["valid_until"].replace("Z", "+00:00")) <= self.now():
            return False
        host = (urlsplit(url).hostname or "").lower()
        matches = [rule for rule in policy["rules"] if use in rule["uses"] and (host == rule["match"]["domain_suffix"] or host.endswith("." + rule["match"]["domain_suffix"]))]
        return bool(matches) and not any(rule["decision"] == "DENY" for rule in matches)

    async def request(self, execution_id: str, operation: str, payload: dict[str, Any]) -> dict[str, Any]:
        if operation in self.unavailable:
            raise ControlUnavailable("CONTROL_UNAVAILABLE")
        async with self._lock:
            entry = self.executions.get(execution_id)
            if entry is None:
                raise ContractError("EXECUTION_GONE")
            if operation != "usage-receipt" and (not entry["active"] or entry["start"]["boot"]["coordinator_boot_id"] != self.boot_id):
                raise ContractError("EXECUTION_GONE")
            self.events.append((execution_id, operation))
            result = self._handle(execution_id, operation, payload)
            if operation in self.lose_response_once:
                self.lose_response_once.remove(operation)
                raise ControlUnavailable("RESPONSE_LOST")
            return deepcopy(result)

    def _handle(self, execution_id: str, operation: str, payload: dict[str, Any]) -> dict[str, Any]:
        entry = self.executions[execution_id]
        start = entry["start"]
        if operation == "source-policy":
            return self.policy
        if operation == "invocation-grants":
            validate_definition("InvocationGrants", payload)
            return {"items": [self._grant(execution_id, item) for item in payload["items"]]}
        if operation == "usage-receipt":
            return self._settle(execution_id, payload)
        if operation == "read-authorization":
            validate_definition("ReadAuthorization", payload)
            key = execution_id, payload["read_id"], payload["url"]
            budget = self.rounds[start["budget"]["round_budget_scope_id"]]
            decision, reason = "DENIED", "SOURCE_NOT_PERMITTED"
            if key in self.read_claims:
                decision, reason = "ALREADY_CLAIMED", None
            elif budget.calls["READ"] >= start["budget"]["limits"]["read_calls"]:
                reason = "CALL_LIMIT"
            else:
                budget.calls["READ"] += 1
                if self.permits(payload["url"], "READ"):
                    self.read_claims.add(key)
                    decision, reason = "GRANTED", None
            return {"read_id": payload["read_id"], "decision": decision, "reason": reason, "policy_epoch": self.policy["policy_epoch"]}
        if operation == "read-receipts":
            validate_definition("ReadReceipts", payload)
            accepted, rejected = [], []
            for receipt in payload["items"]:
                existing_read = self.reads.get(receipt["read_id"])
                if existing_read is not None and existing_read["execution_id"] != execution_id:
                    raise ContractError("BINDING_MISMATCH")
                key = "read:" + receipt["read_id"]
                found, result = self.ledger.lookup(key, receipt)
                if found:
                    (accepted if result else rejected).append(receipt["read_id"] if result else {"read_id": receipt["read_id"], "reason": "READ_NOT_PERMITTED"})
                    continue
                claimed = (execution_id, receipt["read_id"], receipt["final_url"]) in self.read_claims
                allowed = receipt["status"] == "READ_OK" and claimed and self.permits(receipt["final_url"], "READ")
                self.ledger.record(key, receipt, allowed)
                if allowed:
                    self.reads[receipt["read_id"]] = {"execution_id": execution_id, "round_id": start["round_id"], "receipt": deepcopy(receipt)}
                    accepted.append(receipt["read_id"])
                else:
                    rejected.append({"read_id": receipt["read_id"], "reason": "READ_NOT_PERMITTED"})
            return {"accepted_read_ids": accepted, "rejected": rejected}
        if operation == "preference-resolution":
            validate_definition("PreferenceResolutionProposal", payload)
            key = "preference:" + execution_id
            found, result = self.ledger.lookup(key, payload)
            if found:
                return result
            raw = start["input"]["raw"]
            if raw is None or raw["input_digest"] != payload["input_digest"]:
                raise ContractError("BINDING_MISMATCH")
            if self.preference_resolver is None:
                raise ContractError("TEST_RESOLVER_REQUIRED")
            result = self.preference_resolver(deepcopy(raw), deepcopy(payload))
            validate_definition("EffectivePreferenceSnapshot", result)
            entry["effective_preferences"] = result
            self.ledger.record(key, payload, result)
            return result
        if operation == "candidate-validation":
            validate_definition("RecallProposal", payload)
            key = "candidates:" + execution_id
            found, result = self.ledger.lookup(key, payload)
            if found:
                return result
            require_binding(payload["recall_binding"], start["recall_binding"])
            if payload["exclusion_digest"] != start["input"]["exclusion_digest"]:
                raise ContractError("BINDING_MISMATCH")
            effective = entry.get("effective_preferences") or start["input"]["accepted_preferences"]
            if effective is None or payload["effective_preference_digest"] != effective["effective_preference_digest"] or payload["input_digest"] != effective["input_digest"]:
                raise ContractError("BINDING_MISMATCH")
            exclusions = {item["release_group_id"] for item in start["input"]["exclusions"]}
            accepted, rejected = [], []
            for candidate in payload["candidates"]:
                identifier = candidate["release_group_id"]
                if identifier not in self.catalog or identifier in exclusions:
                    rejected.append({"release_group_id": identifier, "reason": "EXCLUDED" if identifier in exclusions else "UNKNOWN_IDENTITY"})
                else:
                    accepted.append({**candidate, "identity": deepcopy(self.catalog[identifier]), "known_review_refs": []})
            bundle_digest = digest(accepted)
            binding = {"candidate_bundle_digest": bundle_digest, "effective_preference_digest": effective["effective_preference_digest"], "exclusion_digest": payload["exclusion_digest"], "selection_config_revision": start["selection_config"]["revision"], "agent_policy_revision": start["agent_policy"]["revision"], "recall_binding": start["recall_binding"]}
            result = {"candidate_bundle_id": "bundle:" + execution_id, "digest": bundle_digest, "binding": binding, "accepted": accepted, "rejected": rejected, "counts": {"proposed": len(payload["candidates"]), "accepted": len(accepted), "rejected": len(rejected)}}
            validate_definition("CandidateBundle", result)
            entry["bundle"] = result
            self.ledger.record(key, payload, result)
            return result
        if operation == "delivery":
            return self._deliver(execution_id, payload)
        raise ContractError("UNKNOWN_CONTROL_OPERATION")

    def _grant(self, execution_id: str, plan: dict[str, Any]) -> dict[str, Any]:
        start = self.executions[execution_id]["start"]
        bindings = [binding for binding in start["model_bindings"].values() if binding["ref"] == plan["model_binding_ref"]]
        if not bindings or not plan["invocation_id"].startswith(execution_id + ":"):
            raise ContractError("BINDING_MISMATCH")
        if plan["lane"] == "FOREGROUND" and plan["run_id"] != start["run_id"]:
            raise ContractError("BINDING_MISMATCH")
        if not any(plan["max_input_tokens"] <= binding["max_input_tokens"] and plan["max_output_tokens"] <= binding["max_output_tokens"] for binding in bindings):
            raise ContractError("POLICY_OUT_OF_RANGE")
        budget = self.rounds[start["budget"]["round_budget_scope_id"]]
        session = self.sessions[budget.session_id]
        lane = budget.lanes[plan["lane"]]
        amount = (plan["max_input_tokens"] + plan["max_output_tokens"]) * self.price_per_token
        denied_reads = [identifier for identifier in plan["read_ids"] if identifier not in self.reads or self.reads[identifier]["round_id"] != start["round_id"] or not self.permits(self.reads[identifier]["receipt"]["final_url"], "AI_PROCESS")]
        reason = None
        existing = self.invocations.get(plan["invocation_id"])
        if existing is not None:
            if existing["plan"] != plan or existing["execution_id"] != execution_id:
                raise ContractError("IDEMPOTENCY_CONFLICT")
            decision, amount = "ALREADY_CLAIMED", existing["reserved"]
        else:
            purpose_scope = plan["run_id"] if plan["lane"] == "FOREGROUND" else "BACKGROUND"
            limit_key = "model_calls" if plan["cost_class"] == "MODEL" else "search_calls"
            if self.price_violation:
                reason = "EXECUTION_NOT_ACTIVE"
            elif denied_reads:
                reason = "SOURCE_NOT_PERMITTED"
            elif session.remaining < amount:
                reason = "SESSION_BUDGET_EXHAUSTED"
            elif budget.total.remaining < amount:
                reason = "ROUND_BUDGET_EXHAUSTED"
            elif lane.remaining < amount:
                reason = "LANE_EXHAUSTED"
            elif budget.calls[plan["cost_class"]] >= start["budget"]["limits"][limit_key]:
                reason = "CALL_LIMIT"
            elif plan["purpose"] == "STRUCTURE_REPAIR" and budget.repairs.get(purpose_scope, 0) >= 1:
                reason = "REPAIR_QUOTA_EXHAUSTED"
            decision = "DENIED" if reason else "GRANTED"
            if reason:
                amount = 0
            else:
                for account in (session, budget.total, lane):
                    account.reserved += amount
                budget.calls[plan["cost_class"]] += 1
                if plan["purpose"] == "STRUCTURE_REPAIR":
                    budget.repairs[purpose_scope] = budget.repairs.get(purpose_scope, 0) + 1
                self.invocations[plan["invocation_id"]] = {"execution_id": execution_id, "round_scope": start["budget"]["round_budget_scope_id"], "plan": deepcopy(plan), "reserved": amount, "settled": False, "uncertain": True}
        return {"invocation_id": plan["invocation_id"], "decision": decision, "reserved_microusd": amount, "deny_reason": reason, "not_permitted_read_ids": denied_reads, "lane_remaining_microusd": lane.remaining, "session_remaining_microusd": session.remaining, "policy_epoch": self.policy["policy_epoch"]}

    def _settle(self, execution_id: str, receipt: dict[str, Any]) -> dict[str, Any]:
        validate_definition("UsageReceipt", receipt)
        key = "usage:" + receipt["invocation_id"] + ":" + receipt["receipt_id"]
        found, result = self.ledger.lookup(key, receipt)
        if found:
            return result
        invocation = self.invocations.get(receipt["invocation_id"])
        if invocation is None or invocation["execution_id"] != execution_id:
            raise ContractError("BINDING_MISMATCH")
        known = receipt["outcome"] == "NOT_PROCESSED" or (receipt["outcome"] == "COMPLETED" and receipt["input_tokens"] is not None and receipt["output_tokens"] is not None)
        if known and not invocation["settled"]:
            charge = 0 if receipt["outcome"] == "NOT_PROCESSED" else (receipt["input_tokens"] + receipt["output_tokens"]) * self.price_per_token
            budget = self.rounds[invocation["round_scope"]]
            for account in (self.sessions[budget.session_id], budget.total, budget.lanes[invocation["plan"]["lane"]]):
                account.reserved -= invocation["reserved"]
                account.settled += charge
            self.price_violation |= charge > invocation["reserved"]
            invocation.update(settled=True, uncertain=False)
            invocation["outcome"] = receipt["outcome"]
            invocation["finish"] = receipt["finish"]
        budget = self.rounds[invocation["round_scope"]]
        lanes = {name: {"reserved_microusd": account.reserved, "settled_microusd": account.settled,
                        "uncertain_invocations": sum(item["uncertain"] for item in self.invocations.values() if item["round_scope"] == invocation["round_scope"] and item["plan"]["lane"] == name)}
                 for name, account in budget.lanes.items()}
        self._settlement_seq += 1
        result = {"accepted": True, "invocation_id": receipt["invocation_id"], "status": "SETTLED" if invocation["settled"] else "UNCERTAIN", "settlement_seq": self._settlement_seq, "lanes": lanes}
        self.ledger.record(key, receipt, result)
        return result

    def _deliver(self, execution_id: str, delivery: dict[str, Any]) -> dict[str, Any]:
        validate_definition("Delivery", delivery)
        key = "delivery:" + delivery["delivery_id"]
        found, result = self.ledger.lookup(key, delivery)
        if found:
            return result
        entry = self.executions[execution_id]
        bundle = entry["bundle"]
        if bundle is None or not delivery["delivery_id"].startswith(execution_id + ":"):
            raise ContractError("BINDING_MISMATCH")
        require_binding(delivery["binding"], bundle["binding"])
        payload = delivery["payload"]
        rows = payload if delivery["kind"] == "SCREENINGS" else payload.get("screenings", []) if isinstance(payload, dict) else []
        for screening in rows:
            if screening["candidate_id"] not in {candidate["release_group_id"] for candidate in bundle["accepted"]}:
                raise ContractError("NOT_IN_BUNDLE")
            self.screenings[(entry["start"]["round_id"], screening["candidate_id"])] = deepcopy(screening)
        items, published = [], []
        if delivery["kind"] == "CARD_BATCH":
            candidates = {item["release_group_id"] for item in bundle["accepted"]}
            for card in payload["cards"]:
                reason = None
                qualified = card["admission"]["qualified_read_ids"]
                reads = [self.reads.get(identifier) for identifier in qualified]
                screening = self.screenings.get((entry["start"]["round_id"], card["candidate_id"]))
                screen_call = self.invocations.get(card["admission"]["screen_invocation_id"])
                if card["candidate_id"] not in candidates:
                    reason = "NOT_IN_BUNDLE"
                elif any(item is None or item["receipt"]["candidate_id"] != card["candidate_id"] for item in reads):
                    reason = "READ_UNKNOWN"
                elif len({item["receipt"]["publisher_key"] for item in reads}) < 2 or any(item["receipt"]["near_duplicate_of"] for item in reads):
                    reason = "ADMISSION_UNVERIFIED"
                elif any(not self.permits(item["receipt"]["final_url"], "AI_PROCESS") for item in reads):
                    reason = "READ_NOT_PERMITTED"
                elif screening is None or screening["screen"]["status"] != "OK" or screening["screen"]["score"] != card["admission"]["screen_score"] or not set(qualified).issubset(screening["qualified_read_ids"]):
                    reason = "ADMISSION_UNVERIFIED"
                elif screen_call is None or screen_call.get("outcome") != "COMPLETED" or screen_call.get("finish") != "COMPLETE":
                    reason = "ADMISSION_UNVERIFIED"
                elif entry["start"]["operation"] != "CURATED_REVIEWS" and card["admission"]["screen_score"] < entry["start"]["agent_policy"]["screening"]["shortlist_min_score"]:
                    reason = "SCORE_BELOW_THRESHOLD"
                elif not 80 <= len(re.findall(r"[\u3400-\u4dbf\u4e00-\u9fff]", card["short_review"]["text"])) <= 180:
                    reason = "LENGTH_INVALID"
                elif any(citation["read_id"] not in qualified for citation in card["short_review"]["citations"]):
                    reason = "CITATION_INVALID"
                items.append({"candidate_id": card["candidate_id"], "decision": "REJECTED" if reason else "ACCEPTED", "reason": reason})
            accepted = {item["candidate_id"] for item in items if item["decision"] == "ACCEPTED"}
            order = payload["screen_order"] if payload["analysis_mode"] != "PERSONALIZED" else [item["candidate_id"] for item in payload["selection"]["order"] if item["verdict"] != "reject"]
            published = [identifier for identifier in order if identifier in accepted and identifier not in entry["display"]["shown_candidate_ids"]][:entry["start"]["selection_config"]["recommendation_target_count"]]
            entry["display"]["shown_candidate_ids"].extend(published)
            entry["display"]["ready_unshown_ids"] = [identifier for identifier in order if identifier in accepted and identifier not in entry["display"]["shown_candidate_ids"]]
            entry["display"]["display_seq"] += 1
        rejected = sum(item["decision"] == "REJECTED" for item in items)
        result = {"status": "REJECTED" if items and rejected == len(items) else "PARTIALLY_ACCEPTED" if rejected else "ACCEPTED", "items": items, "published_candidate_ids": published, "display_state": deepcopy(entry["display"])}
        self.deliveries[delivery["delivery_id"]] = deepcopy(delivery)
        self.ledger.record(key, delivery, result)
        return result
