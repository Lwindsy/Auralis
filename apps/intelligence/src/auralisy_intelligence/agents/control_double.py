"""In-process Java test double. Never use this as product authorization."""

import asyncio
import math
from copy import deepcopy
from dataclasses import dataclass, field
from datetime import datetime, timezone
from fractions import Fraction
from typing import Any, Callable

from auralisy_intelligence.album_reviews.validation import valid_short_length
from auralisy_intelligence.contracts import (
    ContractError,
    IdempotencyLedger,
    content_digest,
    digest,
    require_binding,
    validate_definition,
)
from auralisy_intelligence.recall.full_audience import matches_policy

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
    calls: dict[str, int] = field(
        default_factory=lambda: {"MODEL": 0, "TOOL_SERVICE": 0, "READ": 0}
    )
    repairs: dict[str, int] = field(default_factory=dict)


class JavaControlDouble:
    """Atomic in one event loop; explicitly synthetic prices and catalog."""

    def __init__(
        self,
        coordinator_boot_id: str,
        *,
        catalog: dict[str, dict[str, Any]] | None = None,
        price_per_token: int | Fraction = 1,
        pricing_by_binding: dict[str, dict[str, int]] | None = None,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self.boot_id = coordinator_boot_id
        self.catalog = deepcopy(catalog or {})
        self.candidate_audiences = {}
        self.known_review_refs: dict[str, list[dict[str, str]]] = {}
        self.price_per_token = price_per_token
        self.pricing_by_binding = deepcopy(pricing_by_binding or {})
        required_prices = {
            "input_microusd_per_million",
            "cached_input_microusd_per_million",
            "output_microusd_per_million",
            "search_microusd_per_call",
        }
        if any(
            set(prices) != required_prices
            or any(type(value) is not int or value < 0 for value in prices.values())
            for prices in self.pricing_by_binding.values()
        ):
            raise ValueError("INVALID_BINDING_PRICING")
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.sessions: dict[str, BudgetAccount] = {}
        self.session_scopes: dict[str, str] = {}
        self.round_scopes: dict[str, tuple[str, str]] = {}
        self.rounds: dict[str, RoundBudget] = {}
        self.executions: dict[str, dict[str, Any]] = {}
        self.invocations: dict[str, dict[str, Any]] = {}
        self.reads: dict[str, dict[str, Any]] = {}
        self.deleted_sessions: set[str] = set()
        self.seed_cache: dict[tuple[str, str], dict[str, Any]] = {}
        self.read_claims: set[tuple[str, str, str]] = set()
        self.deliveries: dict[str, dict[str, Any]] = {}
        self.screenings: dict[tuple[str, str], dict[str, Any]] = {}
        self.cards: dict[tuple[str, str], dict[str, Any]] = {}
        self.long_reviews: dict[tuple[str, str], dict[str, Any]] = {}
        self.long_states: dict[tuple[str, str], str] = {}
        self.issued_commands = IdempotencyLedger(20000)
        self.ledger = IdempotencyLedger(20000)
        self.unavailable: set[str] = set()
        self.lose_response_once: set[str] = set()
        self.events: list[tuple[str, str]] = []
        self._lock = asyncio.Lock()
        self.price_violation = False
        self._settlement_seq = 0
        self.preference_resolver: (
            Callable[[dict[str, Any], dict[str, Any]], dict[str, Any]] | None
        ) = None

    def register(self, start: dict[str, Any]) -> None:
        validate_definition("RoundStart", start)
        if start["session_id"] in self.deleted_sessions:
            raise ContractError("EXECUTION_GONE")
        if start["boot"]["coordinator_boot_id"] != self.boot_id:
            raise ContractError("STALE_BOOT")
        identifier = start["execution_id"]
        if identifier in self.executions:
            if self.executions[identifier]["start_digest"] != start["payload_digest"]:
                raise ContractError("IDEMPOTENCY_CONFLICT")
            return
        budget = start["budget"]
        session_id, round_id = budget["session_budget_scope_id"], budget["round_budget_scope_id"]
        if self.session_scopes.get(
            start["session_id"], session_id
        ) != session_id or self.round_scopes.get(
            start["round_id"], (round_id, start["session_id"])
        ) != (round_id, start["session_id"]):
            raise ContractError("BINDING_MISMATCH")
        self.session_scopes[start["session_id"]] = session_id
        self.round_scopes[start["round_id"]] = (round_id, start["session_id"])
        limits = budget["limits"]
        if (
            session_id in self.sessions
            and self.sessions[session_id].cap != limits["session_cap_microusd"]
        ):
            raise ContractError("BINDING_MISMATCH")
        self.sessions.setdefault(session_id, BudgetAccount(limits["session_cap_microusd"]))
        if round_id in self.rounds:
            previous = self.rounds[round_id]
            if (
                previous.session_id != session_id
                or previous.total.cap != limits["round_cap_microusd"]
            ):
                raise ContractError("BINDING_MISMATCH")
        else:
            self.rounds[round_id] = RoundBudget(
                session_id,
                BudgetAccount(limits["round_cap_microusd"]),
                {name: BudgetAccount(cap) for name, cap in limits["lane_caps"].items()},
            )
        continuation = start.get("continuation")
        if continuation is not None:
            previous = next(
                (
                    entry
                    for entry in self.executions.values()
                    if entry["start"]["round_id"] == start["round_id"]
                ),
                None,
            )
            if previous:
                for key in ("selection_config", "agent_policy", "model_bindings", "recall_binding"):
                    require_binding(previous["start"][key], start[key])
                require_binding(previous["bundle"], continuation["candidate_bundle"])
            bundle = deepcopy(continuation["candidate_bundle"])
            display = deepcopy(continuation["display_state"])
        else:
            bundle = None
            display = {
                "display_seq": 0,
                "shown_candidate_ids": [],
                "heard_candidate_ids": [],
                "ready_unshown_ids": [],
                "user_waiting": start["foreground"],
                "run_id": start["run_id"],
            }
        self.executions[identifier] = {
            "start": deepcopy(start),
            "start_digest": start["payload_digest"],
            "active": True,
            "run_active": start["foreground"],
            "bundle": bundle,
            "display": display,
        }

    def seed_ids(self, start):
        raw = start["input"].get("raw")
        if raw is not None:
            return list(
                dict.fromkeys(
                    item["release_group_id"]
                    for item in raw["liked_albums"]
                    if item.get("release_group_id")
                )
            )
        accepted = start["input"].get("accepted_preferences") or {}
        return list(dict.fromkeys(item["id"] for item in accepted.get("seeds", [])))

    def seed_state(self, start):
        items, missing = [], []
        for identifier in self.seed_ids(start):
            identity = self.catalog.get(identifier)
            if identity is None:
                missing.append(identifier)
                continue
            cached = self.seed_cache.get((start["session_id"], identifier))
            if cached and cached["identity"] != identity:
                cached = None
            items.append(
                {
                    "seed_id": identifier,
                    "identity": deepcopy(identity),
                    "evidence": deepcopy(cached),
                }
            )
        return {"items": items, "missing_seed_ids": missing}

    def seed_read_available(self, start, read_id):
        accepted = (
            self.executions[start["execution_id"]].get("effective_preferences")
            or start["input"].get("accepted_preferences")
            or {}
        )
        active = {item["id"] for item in accepted.get("seeds", [])}
        return any(
            row["seed_id"] in active
            and row["identity"] == self.catalog.get(row["seed_id"])
            and any(source["read_id"] == read_id for source in row["sources"])
            for (session, _), row in self.seed_cache.items()
            if session == start["session_id"]
        )

    def delete_session(self, session_id):
        self.deleted_sessions.add(session_id)
        for entry in self.executions.values():
            if entry["start"]["session_id"] == session_id:
                entry["active"] = False
        self.seed_cache = {
            key: value for key, value in self.seed_cache.items() if key[0] != session_id
        }
        self.reads = {
            key: value for key, value in self.reads.items() if value.get("session_id") != session_id
        }

    def close_execution(self, execution_id: str) -> None:
        entry = self.executions[execution_id]
        entry["active"] = False
        entry["run_active"] = False
        entry["start"]["input"]["raw"] = None
        for key, state in list(self.long_states.items()):
            if key[0] == entry["start"]["round_id"] and state == "GENERATING":
                self.long_states[key] = "INTERRUPTED"

    def restart(self, boot_id: str) -> None:
        """Simulate a Java restart retaining its authoritative test ledger."""
        for identifier in self.executions:
            self.close_execution(identifier)
        self.boot_id = boot_id

    def issue_command(
        self, execution_id: str, command: dict[str, Any], *, publish_count: int | None = None
    ) -> dict[str, Any]:
        """Java-side command preparation; the Python executor cannot grant itself a run or money."""
        validate_definition("RoundCommand", command)
        key = execution_id + ":" + command["command_id"]
        found, result = self.issued_commands.lookup(key, command)
        if found:
            return result
        entry = self.executions[execution_id]
        if publish_count is not None:
            if (
                not 1
                <= publish_count
                <= entry["start"]["selection_config"]["recommendation_target_count"]
            ):
                raise ContractError("POLICY_OUT_OF_RANGE")
            entry["publish_limit"] = publish_count
        require_binding(command["boot"], entry["start"]["boot"])
        payload, kind = command["payload"], command["kind"]
        if kind == "DISPLAY_STATE" and payload["display_seq"] <= entry["display"]["display_seq"]:
            self.issued_commands.record(key, command, command)
            return deepcopy(command)
        if kind == "RESCREEN" or kind == "DISPLAY_STATE" and payload["user_waiting"]:
            if entry["run_active"]:
                raise ContractError("EXECUTOR_BUSY")
            if (
                kind == "RESCREEN"
                and datetime.fromisoformat(payload["deadline"].replace("Z", "+00:00")) <= self.now()
            ):
                raise ContractError("DEADLINE_EXCEEDED")
            entry["run_active"] = True
            entry["publish_limit"] = (
                publish_count or entry["start"]["selection_config"]["recommendation_target_count"]
            )
        if kind == "DISPLAY_STATE" and payload["display_seq"] > entry["display"]["display_seq"]:
            entry["display"] = deepcopy(payload)
            if payload["user_waiting"]:
                entry["start"]["run_id"] = payload["run_id"]
        elif kind == "RESCREEN":
            entry["start"]["run_id"] = payload["run_id"]
            entry["display"].update(user_waiting=True, run_id=payload["run_id"])
        elif kind == "BUDGET_UPDATE":
            budget = self.rounds[entry["start"]["budget"]["round_budget_scope_id"]]
            proposed = {name: account.cap + payload[name] for name, account in budget.lanes.items()}
            overflow = sum(proposed.values()) - budget.total.cap
            if overflow > 0:
                donors = [name for name in proposed if payload[name] == 0]
                if len(donors) != 1:
                    raise ContractError("POLICY_OUT_OF_RANGE")
                donor = budget.lanes[donors[0]]
                if donor.remaining < overflow:
                    raise ContractError("POLICY_OUT_OF_RANGE")
                proposed[donors[0]] -= overflow
            if any(cap > 60000 for cap in proposed.values()):
                raise ContractError("POLICY_OUT_OF_RANGE")
            for name, cap in proposed.items():
                budget.lanes[name].cap = cap
        self.issued_commands.record(key, command, command)
        return deepcopy(command)

    def continuation(
        self,
        execution_id: str,
        *,
        new_execution_id: str,
        python_boot_id: str,
        run_id: str | None = None,
        run_kind: str | None = None,
        regenerate: tuple[str, ...] = (),
        rescreen: bool = False,
    ) -> dict[str, Any]:
        entry = self.executions[execution_id]
        start = deepcopy(entry["start"])
        snapshot = deepcopy(
            entry.get("effective_preferences") or start["input"]["accepted_preferences"]
        )
        if snapshot is None or entry["bundle"] is None:
            raise ContractError("CONTINUATION_UNAVAILABLE")
        round_id = start["round_id"]
        rows = []
        for candidate in entry["bundle"]["accepted"]:
            identifier = candidate["release_group_id"]
            screening = self.screenings.get((round_id, identifier))
            if identifier in entry["display"]["shown_candidate_ids"]:
                state = "SHOWN"
            elif identifier in entry["display"]["heard_candidate_ids"]:
                state = "INELIGIBLE"
            elif (round_id, identifier) in self.cards:
                state = "READY"
            elif screening is None or screening["disposition"] == "DEFERRED":
                state = "UNRESEARCHED"
            else:
                state = (
                    "RESERVE"
                    if screening["disposition"] in {"RESERVE", "SHORTLISTED"}
                    else screening["disposition"]
                )
            refs = [
                {
                    "read_id": read_id,
                    "url": value["receipt"]["final_url"],
                    "publisher_key": value["receipt"]["publisher_key"],
                }
                for read_id, value in self.reads.items()
                if value["round_id"] == round_id and value["receipt"]["candidate_id"] == identifier
            ]
            rows.append(
                {
                    "release_group_id": identifier,
                    "recall_rank": candidate["recall_rank"],
                    "state": state,
                    "screening": deepcopy(screening),
                    "read_refs": refs,
                    "long_review_state": self.long_states.get(
                        (round_id, identifier), "NOT_STARTED"
                    ),
                }
            )
        budget = self._budget_state(execution_id)
        start.update(
            execution_id=new_execution_id,
            start_mode="CONTINUE",
            run_id=run_id,
            run_kind=(run_kind or ("RESCREEN" if rescreen else "SUPPLY")) if run_id else None,
            foreground=run_id is not None,
            boot={"python_boot_id": python_boot_id, "coordinator_boot_id": self.boot_id},
            continuation={
                "candidate_bundle": deepcopy(entry["bundle"]),
                "candidates": rows,
                "display_state": deepcopy(entry["display"]),
                "pending": {
                    "rescreen_run_id": run_id if rescreen else None,
                    "regenerate_long_review_ids": list(regenerate),
                    "prioritized_long_review_ids": [],
                },
            },
        )
        start["input"]["raw"] = None
        start["continuation"]["display_state"].update(
            user_waiting=run_id is not None, run_id=run_id
        )
        start["input"]["accepted_preferences"] = snapshot
        start["budget"]["limits"]["lane_caps"] = budget["lane_caps"]
        start["budget"]["remaining_at_issue"] = {
            "session": budget["session_remaining_microusd"],
            "round": budget["round_remaining_microusd"],
            **{
                name: max(
                    0,
                    budget["lane_caps"][name]
                    - values["reserved_microusd"]
                    - values["settled_microusd"],
                )
                for name, values in budget["lanes"].items()
            },
        }
        start["payload_digest"] = content_digest(start)
        validate_definition("RoundStart", start)
        return start

    def _budget_state(self, execution_id: str) -> dict[str, Any]:
        start = self.executions[execution_id]["start"]
        scope = start["budget"]["round_budget_scope_id"]
        budget = self.rounds[scope]
        invocations = [item for item in self.invocations.values() if item["round_scope"] == scope]
        lanes = {
            name: {
                "reserved_microusd": account.reserved,
                "settled_microusd": account.settled,
                "uncertain_invocations": sum(
                    item["uncertain"] for item in invocations if item["plan"]["lane"] == name
                ),
            }
            for name, account in budget.lanes.items()
        }
        usage = [
            item.get("usage", {})
            for item in invocations
            if item.get("usage", {}).get("outcome") != "NOT_PROCESSED"
        ]

        def total(key):
            return (
                None
                if any(item.get(key) is None for item in usage)
                else sum(item[key] for item in usage)
            )

        self._settlement_seq += 1
        return {
            "settlement_seq": self._settlement_seq,
            "session_remaining_microusd": max(0, self.sessions[budget.session_id].remaining),
            "round_remaining_microusd": max(0, budget.total.remaining),
            "lanes": lanes,
            "lane_caps": {name: account.cap for name, account in budget.lanes.items()},
            "structure_repairs": sum(budget.repairs.values()),
            "input_tokens": total("input_tokens"),
            "cached_input_tokens": total("cached_input_tokens"),
        }

    async def request(
        self, execution_id: str, operation: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        if operation in self.unavailable:
            raise ControlUnavailable("CONTROL_UNAVAILABLE")
        async with self._lock:
            entry = self.executions.get(execution_id)
            if entry is None:
                raise ContractError("EXECUTION_GONE")
            if operation not in {"usage-receipt", "budget-state"} and (
                not entry["active"] or entry["start"]["boot"]["coordinator_boot_id"] != self.boot_id
            ):
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
        if operation == "seed-evidence":
            return self.seed_state(start)
        if operation == "seed-evidence-save":
            validate_definition("SeedEvidenceUpdate", payload)
            requested = set(self.seed_ids(start))
            identifiers = [row["seed_id"] for row in payload["items"]]
            if len(identifiers) != len(set(identifiers)):
                raise ContractError("DUPLICATE_SEED")
            for row in payload["items"]:
                if row["seed_id"] not in requested or row["identity"] != self.catalog.get(
                    row["seed_id"]
                ):
                    raise ContractError("BINDING_MISMATCH")
                if (row["status"] == "READ_AVAILABLE") != bool(row["sources"]):
                    raise ContractError("SEED_EVIDENCE_INVALID")
                for source in row["sources"]:
                    read = self.reads.get(source["read_id"])
                    if (
                        read is None
                        or read["session_id"] != start["session_id"]
                        or read["receipt"]["candidate_id"] != row["seed_id"]
                        or read["receipt"]["final_url"] != source["url"]
                        or read["receipt"]["read_at"] != source["read_at"]
                        or source["text_digest"] != digest(source["text"])
                        or len(source["text"].encode("utf-8")) > 3500
                    ):
                        raise ContractError("SEED_EVIDENCE_INVALID")
                previous = self.seed_cache.get((start["session_id"], row["seed_id"]))
                if previous and previous["identity"] == row["identity"] and previous != row:
                    raise ContractError("IDEMPOTENCY_CONFLICT")
            for row in payload["items"]:
                self.seed_cache[(start["session_id"], row["seed_id"])] = deepcopy(row)
            return self.seed_state(start)
        if operation == "budget-state":
            return self._budget_state(execution_id)
        if operation == "invocation-grants":
            validate_definition("InvocationGrants", payload)
            return {"items": [self._grant(execution_id, item) for item in payload["items"]]}
        if operation == "usage-receipt":
            return self._settle(execution_id, payload)
        if operation == "read-authorization":
            validate_definition("ReadAuthorization", payload)
            key = execution_id, payload["read_id"], digest(payload["url"])
            budget = self.rounds[start["budget"]["round_budget_scope_id"]]
            decision, reason = "DENIED", "READ_UNKNOWN"
            if key in self.read_claims:
                decision, reason = "ALREADY_CLAIMED", None
            elif budget.calls["READ"] >= start["budget"]["limits"]["read_calls"]:
                reason = "CALL_LIMIT"
            else:
                budget.calls["READ"] += 1
                self.read_claims.add(key)
                decision, reason = "GRANTED", None
            return {
                "read_id": payload["read_id"],
                "decision": decision,
                "reason": reason,
            }
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
                    (accepted if result else rejected).append(
                        receipt["read_id"]
                        if result
                        else {"read_id": receipt["read_id"], "reason": "READ_NOT_PERMITTED"}
                    )
                    continue
                claimed = (
                    execution_id,
                    receipt["read_id"],
                    digest(receipt["final_url"]),
                ) in self.read_claims
                allowed = receipt["status"] == "READ_OK" and claimed
                self.ledger.record(key, receipt, allowed)
                if allowed:
                    self.reads[receipt["read_id"]] = {
                        "execution_id": execution_id,
                        "round_id": start["round_id"],
                        "session_id": start["session_id"],
                        "receipt": deepcopy(receipt),
                    }
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
            if (
                effective is None
                or payload["effective_preference_digest"]
                != effective["effective_preference_digest"]
                or payload["input_digest"] != effective["input_digest"]
            ):
                raise ContractError("BINDING_MISMATCH")
            exclusions = {item["release_group_id"] for item in start["input"]["exclusions"]}
            accepted, rejected = [], []
            for candidate in payload["candidates"]:
                identifier = candidate["release_group_id"]
                if identifier not in self.catalog or identifier in exclusions:
                    rejected.append(
                        {
                            "release_group_id": identifier,
                            "reason": "EXCLUDED"
                            if identifier in exclusions
                            else "UNKNOWN_IDENTITY",
                        }
                    )
                else:
                    accepted.append(
                        {
                            **candidate,
                            "identity": deepcopy(self.catalog[identifier]),
                            "known_review_refs": deepcopy(
                                self.known_review_refs.get(identifier, [])
                            ),
                        }
                    )
            for candidate in accepted:
                if candidate["release_group_id"] in self.candidate_audiences:
                    candidate["audience"] = deepcopy(
                        self.candidate_audiences[candidate["release_group_id"]]
                    )
            bundle_digest = digest(accepted)
            binding = {
                "candidate_bundle_digest": bundle_digest,
                "effective_preference_digest": effective["effective_preference_digest"],
                "exclusion_digest": payload["exclusion_digest"],
                "selection_config_revision": start["selection_config"]["revision"],
                "agent_policy_revision": start["agent_policy"]["revision"],
                "recall_binding": start["recall_binding"],
            }
            result = {
                "candidate_bundle_id": "bundle:" + execution_id,
                "digest": bundle_digest,
                "binding": binding,
                "accepted": accepted,
                "rejected": rejected,
                "counts": {
                    "proposed": len(payload["candidates"]),
                    "accepted": len(accepted),
                    "rejected": len(rejected),
                },
            }
            validate_definition("CandidateBundle", result)
            entry["bundle"] = result
            self.ledger.record(key, payload, result)
            return result
        if operation == "delivery":
            return self._deliver(execution_id, payload)
        raise ContractError("UNKNOWN_CONTROL_OPERATION")

    def _grant(self, execution_id: str, plan: dict[str, Any]) -> dict[str, Any]:
        start = self.executions[execution_id]["start"]
        if (
            datetime.fromisoformat(start["deadlines"]["round_deadline_at"].replace("Z", "+00:00"))
            <= self.now()
        ):
            raise ContractError("DEADLINE_EXCEEDED")
        bindings = [
            binding
            for binding in start["model_bindings"].values()
            if binding["ref"] == plan["model_binding_ref"]
        ]
        if not bindings or not plan["invocation_id"].startswith(execution_id + ":"):
            raise ContractError("BINDING_MISMATCH")
        if plan["lane"] == "FOREGROUND" and plan["run_id"] != start["run_id"]:
            raise ContractError("BINDING_MISMATCH")
        if not any(
            plan["max_input_tokens"] <= binding["max_input_tokens"]
            and plan["max_output_tokens"] <= binding["max_output_tokens"]
            for binding in bindings
        ):
            raise ContractError("POLICY_OUT_OF_RANGE")
        budget = self.rounds[start["budget"]["round_budget_scope_id"]]
        session = self.sessions[budget.session_id]
        lane = budget.lanes[plan["lane"]]
        amount = self._reservation_amount(plan)
        denied_reads = [
            identifier
            for identifier in plan["read_ids"]
            if identifier not in self.reads
            or (
                self.reads[identifier]["round_id"] != start["round_id"]
                and not self.seed_read_available(start, identifier)
            )
        ]
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
                reason = "READ_UNKNOWN"
            elif session.remaining < amount:
                reason = "SESSION_BUDGET_EXHAUSTED"
            elif budget.total.remaining < amount:
                reason = "ROUND_BUDGET_EXHAUSTED"
            elif lane.remaining < amount:
                reason = "LANE_EXHAUSTED"
            elif budget.calls[plan["cost_class"]] >= start["budget"]["limits"][limit_key]:
                reason = "CALL_LIMIT"
            elif (
                plan["purpose"] == "STRUCTURE_REPAIR" and budget.repairs.get(purpose_scope, 0) >= 1
            ):
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
                self.invocations[plan["invocation_id"]] = {
                    "execution_id": execution_id,
                    "round_scope": start["budget"]["round_budget_scope_id"],
                    "plan": deepcopy(plan),
                    "reserved": amount,
                    "settled": False,
                    "uncertain": True,
                }
        return {
            "invocation_id": plan["invocation_id"],
            "decision": decision,
            "reserved_microusd": amount,
            "deny_reason": reason,
            "not_permitted_read_ids": denied_reads,
            "lane_remaining_microusd": lane.remaining,
            "session_remaining_microusd": session.remaining,
        }

    def _settle(self, execution_id: str, receipt: dict[str, Any]) -> dict[str, Any]:
        validate_definition("UsageReceipt", receipt)
        key = "usage:" + receipt["invocation_id"] + ":" + receipt["receipt_id"]
        found, result = self.ledger.lookup(key, receipt)
        if found:
            return result
        invocation = self.invocations.get(receipt["invocation_id"])
        if invocation is None or invocation["execution_id"] != execution_id:
            raise ContractError("BINDING_MISMATCH")
        if not invocation["settled"]:
            invocation["usage"] = {
                key: receipt[key]
                for key in (
                    "outcome",
                    "finish",
                    "input_tokens",
                    "cached_input_tokens",
                    "output_tokens",
                )
            }
            if invocation.get("outcome") != "COMPLETED" or receipt["outcome"] == "COMPLETED":
                invocation["outcome"] = receipt["outcome"]
                invocation["finish"] = receipt["finish"]
        known = receipt["outcome"] == "NOT_PROCESSED" or (
            receipt["outcome"] == "COMPLETED"
            and receipt["input_tokens"] is not None
            and receipt["output_tokens"] is not None
        )
        if known and not invocation["settled"]:
            charge = (
                0
                if receipt["outcome"] == "NOT_PROCESSED"
                else self._charge_amount(invocation["plan"], receipt)
            )
            budget = self.rounds[invocation["round_scope"]]
            for account in (
                self.sessions[budget.session_id],
                budget.total,
                budget.lanes[invocation["plan"]["lane"]],
            ):
                account.reserved -= invocation["reserved"]
                account.settled += charge
            self.price_violation |= charge > invocation["reserved"]
            invocation.update(settled=True, uncertain=False, charge=charge)
            invocation["outcome"] = receipt["outcome"]
            invocation["finish"] = receipt["finish"]
        budget = self.rounds[invocation["round_scope"]]
        lanes = {
            name: {
                "reserved_microusd": account.reserved,
                "settled_microusd": account.settled,
                "uncertain_invocations": sum(
                    item["uncertain"]
                    for item in self.invocations.values()
                    if item["round_scope"] == invocation["round_scope"]
                    and item["plan"]["lane"] == name
                ),
            }
            for name, account in budget.lanes.items()
        }
        self._settlement_seq += 1
        result = {
            "accepted": True,
            "invocation_id": receipt["invocation_id"],
            "status": "SETTLED" if invocation["settled"] else "UNCERTAIN",
            "settlement_seq": self._settlement_seq,
            "lanes": lanes,
        }
        self.ledger.record(key, receipt, result)
        return result

    def _reservation_amount(self, plan: dict[str, Any]) -> int:
        prices = self.pricing_by_binding.get(plan["model_binding_ref"])
        if prices is None:
            return math.ceil(
                (plan["max_input_tokens"] + plan["max_output_tokens"]) * self.price_per_token
            )
        token_cost = (
            plan["max_input_tokens"] * prices["input_microusd_per_million"]
            + plan["max_output_tokens"] * prices["output_microusd_per_million"]
        )
        search_cost = (
            prices["search_microusd_per_call"] if plan["cost_class"] == "TOOL_SERVICE" else 0
        )
        return search_cost + math.ceil(token_cost / 1_000_000)

    def _charge_amount(self, plan: dict[str, Any], receipt: dict[str, Any]) -> int:
        prices = self.pricing_by_binding.get(plan["model_binding_ref"])
        if prices is None:
            return math.ceil(
                (receipt["input_tokens"] + receipt["output_tokens"]) * self.price_per_token
            )
        cached = min(receipt["cached_input_tokens"] or 0, receipt["input_tokens"])
        uncached = receipt["input_tokens"] - cached
        token_cost = (
            uncached * prices["input_microusd_per_million"]
            + cached * prices["cached_input_microusd_per_million"]
            + receipt["output_tokens"] * prices["output_microusd_per_million"]
        )
        search_cost = (
            prices["search_microusd_per_call"] if plan["cost_class"] == "TOOL_SERVICE" else 0
        )
        return search_cost + math.ceil(token_cost / 1_000_000)

    def _audience_permits(self, entry, card):
        candidate = next(
            (
                c
                for c in entry["bundle"]["accepted"]
                if c["release_group_id"] == card["candidate_id"]
            ),
            None,
        )
        audience = candidate.get("audience") if candidate else None
        policy = entry["start"]["agent_policy"]
        return bool(
            audience
            and audience == card["admission"].get("audience")
            and matches_policy(audience, policy)
            and audience["listeners"] is not None
            and audience["listeners"] >= policy["audience_min_listeners"]
            and audience["listeners"] <= policy.get("audience_max_listeners", float("inf"))
        )

    def _deliver(self, execution_id: str, delivery: dict[str, Any]) -> dict[str, Any]:
        validate_definition("Delivery", delivery)
        key = "delivery:" + delivery["delivery_id"]
        found, result = self.ledger.lookup(key, delivery)
        if found:
            return result
        entry = self.executions[execution_id]
        bundle = entry["bundle"]
        if (
            not delivery["delivery_id"].startswith(execution_id + ":")
            or delivery["round_id"] != entry["start"]["round_id"]
        ):
            raise ContractError("BINDING_MISMATCH")
        if delivery["binding"] is None:
            if delivery["kind"] != "ROUND_REPORT" and (
                delivery["kind"] != "CARD_BATCH" or delivery["payload"]["outcome"] == "DISPLAYABLE"
            ):
                raise ContractError("BINDING_MISMATCH")
        else:
            if bundle is None:
                raise ContractError("BINDING_MISMATCH")
            require_binding(delivery["binding"], bundle["binding"])
        payload = delivery["payload"]
        rows = (
            payload
            if delivery["kind"] == "SCREENINGS"
            else payload.get("screenings", [])
            if isinstance(payload, dict)
            else []
        )
        for screening in rows:
            if screening["candidate_id"] not in {
                candidate["release_group_id"] for candidate in bundle["accepted"]
            }:
                raise ContractError("NOT_IN_BUNDLE")
            self.screenings[(entry["start"]["round_id"], screening["candidate_id"])] = deepcopy(
                screening
            )
        items, published = [], []
        if delivery["kind"] == "CARD_BATCH":
            candidates = (
                {item["release_group_id"] for item in bundle["accepted"]} if bundle else set()
            )
            for card in payload["cards"]:
                reason = None
                qualified = card["admission"]["qualified_read_ids"]
                reads = [self.reads.get(identifier) for identifier in qualified]
                screening = self.screenings.get((entry["start"]["round_id"], card["candidate_id"]))
                screen_call = self.invocations.get(card["admission"]["screen_invocation_id"])
                if card["candidate_id"] not in candidates:
                    reason = "NOT_IN_BUNDLE"
                elif card["candidate_id"] in entry["display"]["heard_candidate_ids"]:
                    reason = "EXCLUDED"
                elif "audience_min_listeners" in entry["start"][
                    "agent_policy"
                ] and not self._audience_permits(entry, card):
                    reason = "ADMISSION_UNVERIFIED"
                elif any(
                    item is None or item["receipt"]["candidate_id"] != card["candidate_id"]
                    for item in reads
                ):
                    reason = "READ_UNKNOWN"
                elif not reads or any(item["receipt"]["near_duplicate_of"] for item in reads):
                    reason = "ADMISSION_UNVERIFIED"
                elif (
                    screening is None
                    or screening["screen"]["status"] != "OK"
                    or screening["screen"]["score"] != card["admission"]["screen_score"]
                    or not set(qualified).issubset(screening["qualified_read_ids"])
                ):
                    reason = "ADMISSION_UNVERIFIED"
                elif (
                    screen_call is None
                    or screen_call.get("outcome") != "COMPLETED"
                    or screen_call.get("finish") != "COMPLETE"
                ):
                    reason = "ADMISSION_UNVERIFIED"
                elif (
                    card["admission"]["screen_score"]
                    < entry["start"]["agent_policy"]["screening"][
                        "supplement_min_score"
                        if payload["batch_kind"] == "SUPPLY"
                        else "shortlist_min_score"
                    ]
                ):
                    reason = "SCORE_BELOW_THRESHOLD"
                elif not valid_short_length(
                    card["short_review"]["text"],
                    entry["start"]["agent_policy"]["writing"],
                    english=entry["start"]["input"].get("language") == "en",
                ):
                    reason = "LENGTH_INVALID"
                elif any(
                    citation["read_id"] not in qualified
                    for citation in card["short_review"]["citations"]
                ):
                    reason = "CITATION_INVALID"
                items.append(
                    {
                        "candidate_id": card["candidate_id"],
                        "decision": "REJECTED" if reason else "ACCEPTED",
                        "reason": reason,
                    }
                )
            accepted = {item["candidate_id"] for item in items if item["decision"] == "ACCEPTED"}
            order = (
                payload["screen_order"]
                if payload["analysis_mode"] != "PERSONALIZED"
                else [
                    item["candidate_id"]
                    for item in payload["selection"]["order"]
                    if item["verdict"] != "reject"
                ]
            )
            for card in payload["cards"]:
                if card["candidate_id"] in accepted:
                    self.cards[(entry["start"]["round_id"], card["candidate_id"])] = deepcopy(card)
                    self.long_states.setdefault(
                        (entry["start"]["round_id"], card["candidate_id"]),
                        card["long_review_state"],
                    )
            combined = list(
                dict.fromkeys(
                    entry["display"]["ready_unshown_ids"]
                    + [identifier for identifier in order if identifier in accepted]
                )
            )
            combined = [
                identifier
                for identifier in combined
                if identifier not in entry["display"]["shown_candidate_ids"]
                and identifier not in entry["display"]["heard_candidate_ids"]
            ]
            combined.sort(
                key=lambda identifier: (
                    self.cards.get((entry["start"]["round_id"], identifier), {}).get(
                        "selection_basis"
                    )
                    == "SCREEN_ONLY"
                )
            )
            can_publish = payload["batch_kind"] == "FIRST" or (
                delivery["run_id"] is not None
                and delivery["run_id"] == entry["start"]["run_id"]
                and entry["display"]["user_waiting"]
            )
            limit = (
                entry["start"]["selection_config"]["recommendation_target_count"]
                if payload["batch_kind"] == "FIRST"
                else entry.get(
                    "publish_limit",
                    entry["start"]["selection_config"]["recommendation_target_count"],
                )
            )
            published = combined[:limit] if can_publish else []
            entry["display"]["shown_candidate_ids"].extend(published)
            entry["display"]["ready_unshown_ids"] = [
                identifier
                for identifier in combined
                if identifier not in entry["display"]["shown_candidate_ids"]
            ]
            if can_publish:
                entry["display"].update(user_waiting=False, run_id=None)
                entry["run_active"] = False
            entry["display"]["display_seq"] += 1
        elif delivery["kind"] == "ORDER_UPDATE":
            if payload["run_id"] is not None and payload["run_id"] != entry["start"]["run_id"]:
                raise ContractError("BINDING_MISMATCH")
            if payload["basis_display_seq"] != entry["display"]["display_seq"]:
                raise ContractError("BINDING_MISMATCH")
            ready = set(entry["display"]["ready_unshown_ids"])
            ids = [item["candidate_id"] for item in payload["order"]]
            if (
                len(ids) != len(set(ids))
                or not set(ids).issubset(ready)
                or set(ids) & set(entry["display"]["shown_candidate_ids"])
            ):
                raise ContractError("BINDING_MISMATCH")
            accepted_order = [
                item["candidate_id"] for item in payload["order"] if item["verdict"] != "reject"
            ]
            rejected_ids = {
                item["candidate_id"] for item in payload["order"] if item["verdict"] == "reject"
            }
            for identifier in rejected_ids:
                candidate_key = entry["start"]["round_id"], identifier
                self.cards.pop(candidate_key, None)
                if candidate_key in self.screenings:
                    self.screenings[candidate_key].update(
                        disposition="INELIGIBLE", reason_code="SELECT_REJECTED"
                    )
            combined = accepted_order + [
                identifier
                for identifier in entry["display"]["ready_unshown_ids"]
                if identifier not in ids
            ]
            filtered = []
            for identifier in combined:
                card = self.cards.get((entry["start"]["round_id"], identifier))
                permitted = (
                    card is not None
                    and identifier not in entry["display"]["heard_candidate_ids"]
                    and all(
                        read_id in self.reads for read_id in card["admission"]["qualified_read_ids"]
                    )
                )
                if permitted:
                    filtered.append(identifier)
                    items.append(
                        {"candidate_id": identifier, "decision": "ACCEPTED", "reason": None}
                    )
                else:
                    items.append(
                        {
                            "candidate_id": identifier,
                            "decision": "REJECTED",
                            "reason": "READ_NOT_PERMITTED",
                        }
                    )
            combined = filtered
            if payload["run_id"] is not None:
                if payload["run_id"] != entry["start"]["run_id"]:
                    raise ContractError("BINDING_MISMATCH")
                published = combined[
                    : entry["start"]["selection_config"]["recommendation_target_count"]
                ]
                entry["display"]["shown_candidate_ids"].extend(published)
                entry["display"].update(user_waiting=False, run_id=None)
                entry["run_active"] = False
            entry["display"]["ready_unshown_ids"] = [
                identifier
                for identifier in combined
                if identifier not in entry["display"]["shown_candidate_ids"]
            ]
            entry["display"]["display_seq"] += 1
        elif delivery["kind"] == "LONG_REVIEW":
            identifier = payload["candidate_id"]
            reason = None
            if identifier not in {item["release_group_id"] for item in bundle["accepted"]}:
                reason = "NOT_IN_BUNDLE"
            elif payload["status"] == "READY":
                for citation in payload["citations"]:
                    read = self.reads.get(citation["read_id"])
                    if (
                        read is None
                        or read["round_id"] != entry["start"]["round_id"]
                        or read["receipt"]["candidate_id"] != identifier
                    ):
                        reason = "READ_UNKNOWN"
                        break
            candidate_key = entry["start"]["round_id"], identifier
            items = [
                {
                    "candidate_id": identifier,
                    "decision": "REJECTED" if reason else "ACCEPTED",
                    "reason": reason,
                }
            ]
            self.long_states[candidate_key] = "FAILED" if reason else payload["status"]
            if reason:
                self.long_reviews.pop(candidate_key, None)
            else:
                self.long_reviews[candidate_key] = deepcopy(payload)
        elif delivery["kind"] == "ROUND_REPORT":
            self.close_execution(execution_id)
        rejected = sum(item["decision"] == "REJECTED" for item in items)
        result = {
            "status": "REJECTED"
            if items and rejected == len(items)
            else "PARTIALLY_ACCEPTED"
            if rejected
            else "ACCEPTED",
            "items": items,
            "published_candidate_ids": published,
            "display_state": deepcopy(entry["display"]),
        }
        stored = deepcopy(delivery)
        if result["status"] == "REJECTED" and delivery["kind"] == "LONG_REVIEW":
            stored["payload"].update(
                status="FAILED", sections=None, citations=[], reason_code="PUBLICATION_REJECTED"
            )
            stored["payload_digest"] = digest(stored["payload"])
        self.deliveries[delivery["delivery_id"]] = stored
        self.ledger.record(key, delivery, result)
        return result
