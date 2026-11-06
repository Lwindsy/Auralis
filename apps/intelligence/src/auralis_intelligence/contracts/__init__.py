"""Shared wire validation; schemas are registered by the workspace contracts entry."""

from copy import deepcopy
from functools import lru_cache
from hashlib import sha256
from importlib.resources import files
import json
from typing import Any, Mapping

from jsonschema import Draft202012Validator, FormatChecker
import rfc8785


class ContractError(ValueError):
    """A safe code without user text, provider content, or validation payloads."""


@lru_cache(maxsize=1)
def schema() -> dict[str, Any]:
    return json.loads(files(__package__).joinpath("agent-v1.schema.json").read_text(encoding="utf-8"))


def digest(value: Any) -> str:
    """Hash RFC 8785 canonical JSON, never JSON's incidental key ordering."""
    try:
        canonical = rfc8785.dumps(value)
    except rfc8785.CanonicalizationError as error:
        raise ContractError("INVALID_CANONICAL_JSON") from error
    return "sha256:" + sha256(canonical).hexdigest()


def content_digest(payload: Mapping[str, Any], field: str = "payload_digest") -> str:
    return digest({key: value for key, value in payload.items() if key != field})


def validate_definition(name: str, payload: Any) -> None:
    """Validate a shared definition and its local, deterministic invariants."""
    document = schema()
    if name not in document["$defs"]:
        raise ContractError("UNKNOWN_MESSAGE")
    validator = Draft202012Validator(
        {**document, "$ref": "#/$defs/" + name}, format_checker=FormatChecker()
    )
    if next(validator.iter_errors(payload), None) is not None:
        raise ContractError("INVALID_PAYLOAD")
    _semantic(name, payload)


def validate_message(message: Mapping[str, Any]) -> None:
    validate_definition("Envelope", message)
    validate_definition(message["message_type"], message["payload"])


def envelope(name: str, payload: Any, request_id: str, trace_id: str = "local", span_id: str = "local") -> dict[str, Any]:
    result = {
        "schema_version": "intel.v1", "contract_revision": "0.14",
        "request_id": request_id, "trace_context": {"trace_id": trace_id, "span_id": span_id},
        "message_type": name, "payload": deepcopy(payload),
    }
    validate_message(result)
    return result


def require_binding(actual: Mapping[str, Any], expected: Mapping[str, Any]) -> None:
    if actual != expected:
        raise ContractError("BINDING_MISMATCH")


def _unique(rows: list[dict[str, Any]], key: str) -> None:
    values = [row[key] for row in rows]
    if len(values) != len(set(values)):
        raise ContractError("DUPLICATE_ID")


def _semantic(name: str, value: Any) -> None:
    if name == "RoundStart":
        config = value["selection_config"]
        if not config["recommendation_target_count"] <= config["shortlist_target_count"] <= config["recall_target_count"]:
            raise ContractError("POLICY_OUT_OF_RANGE")
        if content_digest(value) != value["payload_digest"]:
            raise ContractError("BINDING_MISMATCH")
        policy = value["agent_policy"]
        if content_digest(policy, "digest") != policy["digest"]:
            raise ContractError("BINDING_MISMATCH")
        raw = value["input"]["raw"]
        accepted = value["input"]["accepted_preferences"]
        if raw is not None and (value["start_mode"] != "FRESH" or value["round_seq"] != 1 or accepted is not None):
            raise ContractError("RAW_TEXT_LIFETIME")
        if raw is None and accepted is None:
            raise ContractError("MISSING_PREFERENCES")
        if (value["start_mode"] == "CONTINUE") != (value["continuation"] is not None):
            raise ContractError("INVALID_CONTINUATION")
        if value["start_mode"] == "CONTINUE" and accepted is None:
            raise ContractError("MISSING_PREFERENCES")
        if value["foreground"] and (value["run_id"] is None or value["run_kind"] is None):
            raise ContractError("MISSING_RUN")
        if (value["operation"] == "CURATED_REVIEWS") != (value["curated_candidates"] is not None):
            raise ContractError("INVALID_CURATED_INPUT")
        if digest(value["input"]["exclusions"]) != value["input"]["exclusion_digest"]:
            raise ContractError("BINDING_MISMATCH")
        limits = value["budget"]["limits"]
        if sum(limits["lane_caps"].values()) > limits["round_cap_microusd"] or limits["round_cap_microusd"] > limits["session_cap_microusd"]:
            raise ContractError("POLICY_OUT_OF_RANGE")
        if policy["concurrency"]["background_model_max_in_flight"] > policy["concurrency"]["model_max_in_flight"]:
            raise ContractError("POLICY_OUT_OF_RANGE")
        if policy["screening"]["reads_per_album_initial"] > policy["screening"]["max_reads_per_album"]:
            raise ContractError("POLICY_OUT_OF_RANGE")
        if accepted is not None:
            _semantic("EffectivePreferenceSnapshot", accepted)
    elif name in {"PreferenceResolutionProposal", "RecallProposal"}:
        if content_digest(value, "proposal_digest") != value["proposal_digest"]:
            raise ContractError("BINDING_MISMATCH")
        if name == "RecallProposal":
            _unique(value["candidates"], "release_group_id")
            if value["returned_count"] != len(value["candidates"]) or value["shortfall"] != value["requested_count"] - value["returned_count"]:
                raise ContractError("COUNT_MISMATCH")
            ranks = [item["recall_rank"] for item in value["candidates"]]
            if ranks != list(range(1, len(ranks) + 1)):
                raise ContractError("RANK_MISMATCH")
        else:
            for item in value["preferences"] + value["constraints"]:
                if item["text_span"]["start"] >= item["text_span"]["end"]:
                    raise ContractError("INVALID_TEXT_SPAN")
    elif name == "EffectivePreferenceSnapshot":
        if content_digest(value, "effective_preference_digest") != value["effective_preference_digest"]:
            raise ContractError("BINDING_MISMATCH")
        for key in ("seeds", "tags"):
            _unique(value[key], "id")
        if value["has_positive_signal"] != bool(value["seeds"] or value["tags"]):
            raise ContractError("POSITIVE_SIGNAL_MISMATCH")
        if set(value["required_tags"]) & set(value["forbidden_tags"]):
            raise ContractError("PREFERENCE_CONFLICT")
    elif name == "Delivery":
        if digest(value["payload"]) != value["payload_digest"]:
            raise ContractError("BINDING_MISMATCH")
        if value["kind"] == "CARD_BATCH":
            cards = value["payload"]["cards"]
            _unique(cards, "candidate_id")
            if value["payload"]["outcome"] == "DISPLAYABLE" and not cards:
                raise ContractError("EMPTY_DISPLAYABLE")
            if value["payload"]["outcome"] != "DISPLAYABLE" and value["payload"]["reason_code"] is None:
                raise ContractError("MISSING_REASON")
    elif name == "RoundCommand":
        if digest(value["payload"]) != value["payload_digest"]:
            raise ContractError("BINDING_MISMATCH")
    elif name == "InvocationGrants":
        _unique(value["items"], "invocation_id")
        for item in value["items"]:
            if item["lane"] == "FOREGROUND" and item["run_id"] is None:
                raise ContractError("MISSING_RUN")
    elif name == "ReadReceipts":
        _unique(value["items"], "read_id")
        for item in value["items"]:
            _semantic("ReadReceipt", item)
    elif name == "ReadReceipt" and value["status"] == "READ_OK":
        if any(value[k] is None for k in ("final_url", "publisher_key", "content_digest")) or value["text_chars"] == 0:
            raise ContractError("INCOMPLETE_READ")


class IdempotencyLedger:
    """Bounded execution-local duplicate detection, not persistent Java storage."""

    def __init__(self, max_entries: int = 4096) -> None:
        self.max_entries = max_entries
        self._entries: dict[str, tuple[str, Any]] = {}

    def lookup(self, key: str, payload: Any) -> tuple[bool, Any]:
        existing = self._entries.get(key)
        if existing is None:
            return False, None
        if existing[0] != digest(payload):
            raise ContractError("IDEMPOTENCY_CONFLICT")
        return True, deepcopy(existing[1])

    def record(self, key: str, payload: Any, result: Any) -> None:
        found, _ = self.lookup(key, payload)
        if found:
            return
        if len(self._entries) >= self.max_entries:
            raise ContractError("LEDGER_CAPACITY")
        self._entries[key] = (digest(payload), deepcopy(result))

    def clear(self) -> None:
        self._entries.clear()
