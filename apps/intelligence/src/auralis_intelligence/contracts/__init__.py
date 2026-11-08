"""Shared wire validation; schemas are registered by the workspace contracts entry."""

import json
from copy import deepcopy
from datetime import datetime
from functools import lru_cache
from hashlib import sha256
from importlib.resources import files
from typing import Any, Mapping

import rfc8785
from jsonschema import Draft202012Validator, FormatChecker


class ContractError(ValueError):
    """A safe code without user text, provider content, or validation payloads."""


@lru_cache(maxsize=1)
def schema() -> dict[str, Any]:
    return json.loads(
        files(__package__).joinpath("agent-v1.schema.json").read_text(encoding="utf-8")
    )


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


def envelope(
    name: str, payload: Any, request_id: str, trace_id: str = "local", span_id: str = "local"
) -> dict[str, Any]:
    result = {
        "schema_version": "intel.v1",
        "contract_revision": "0.29",
        "request_id": request_id,
        "trace_context": {"trace_id": trace_id, "span_id": span_id},
        "message_type": name,
        "payload": deepcopy(payload),
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
    if name == "RuntimeView":
        _unique(value["active"], "execution_id")
        if len(value["active"]) > value["execution_capacity"] or any(
            row["slot_released"] or row["boot_id"] != value["boot_id"] for row in value["active"]
        ):
            raise ContractError("BINDING_MISMATCH")
    elif name == "ExecutionAdmission":
        view = value["execution"]
        status = value["status"]
        if (status in {"ACTIVE", "TERMINAL"}) != (view is not None):
            raise ContractError("BINDING_MISMATCH")
        if (status == "UNKNOWN") != (value["dedup_until"] is None):
            raise ContractError("BINDING_MISMATCH")
        if view is not None and (
            view["execution_id"] != value["execution_id"]
            or view["boot_id"] != value["boot_id"]
            or (status == "TERMINAL") != view["slot_released"]
        ):
            raise ContractError("BINDING_MISMATCH")
    elif name == "ProductState":
        admission = value["admission"]
        if (value["status"] == "WAITING") != (
            admission is not None and admission["status"] == "WAITING"
        ):
            raise ContractError("BINDING_MISMATCH")
        if admission is not None:
            _semantic("ProductAdmission", admission)
    elif name == "ProductAdmission":
        if _time(value["accepted_at"]) >= _time(value["queue_deadline"]):
            raise ContractError("INVALID_DEADLINE")
    elif name == "BootstrapState":
        _semantic("ProductState", value["state"])
    elif name == "DiscoverySubmission":
        if value["expected_session_id"] is None and value["expected_revision"] != 0:
            raise ContractError("BINDING_MISMATCH")
        _semantic("DiscoveryInput", value["input"])
    elif name == "DiscoveryInput":
        if not (
            value["liked_genres"]
            or value["disliked_genre_ids"]
            or value["liked_albums"]
            or value["request_text"].strip()
        ):
            raise ContractError("MISSING_INPUT")
    elif name in {"AlbumSearchResult", "GenreSearchResult"}:
        key = "mb_release_group_id" if name == "AlbumSearchResult" else "genre_id"
        _unique(value["items"], key)
        if len(value["items"]) > value["limit"] or (
            value["next_offset"] is not None and value["next_offset"] <= value["offset"]
        ):
            raise ContractError("COUNT_MISMATCH")
    elif name == "ResolvedAlbum":
        album, raw = value["album"], value["input"]
        if album["in_core_catalog"]:
            valid = (
                raw["release_group_id"] == album["mb_release_group_id"] and raw["external"] is None
            )
        else:
            valid = (
                raw["release_group_id"] is None
                and raw["external"] is not None
                and raw["external"]["mb_release_group_id"] == album["mb_release_group_id"]
                and set(raw["external"]["mapped_genre_ids"]).issubset(
                    row["genre_id"] for row in album["identity"]["primary_genres"]
                )
            )
        if not valid:
            raise ContractError("BINDING_MISMATCH")
    elif name == "SeedEvidenceCopy":
        source, target = value["source"], value["target"]
        copied = _time(value["copied_at"])
        if (
            source["subject_id"] != target["subject_id"]
            or source["session_id"] == target["session_id"]
            or any(owner["subject_status"] != "ACTIVE" for owner in (source, target))
            or source["session_status"] not in {"ACTIVE", "SUPERSEDED"}
            or target["session_status"] != "ACTIVE"
            or copied >= min(_time(source["expires_at"]), _time(target["expires_at"]))
        ):
            raise ContractError("BINDING_MISMATCH")
        _unique(value["items"], "seed_id")
        _unique(value["items"], "target_read_id")
        for row in value["items"]:
            if (
                row["source_read_id"] == row["target_read_id"]
                or not _time(row["read_at"]) <= copied < _time(row["expires_at"])
                or _time(row["expires_at"]) > _time(target["expires_at"])
            ):
                raise ContractError("BINDING_MISMATCH")
    elif name == "FullAudienceSnapshot":
        _unique(value["counts"], "release_group_id")
        ids = [row["release_group_id"] for row in value["counts"]]
        if (
            ids != sorted(ids)
            or any(identifier != identifier.lower() for identifier in ids)
            or any(row["listeners"] > value["sample_listeners"] for row in value["counts"])
            or value["dataset_id"] != "core:" + value["source_manifest_sha256"]
            or content_digest(value, "digest") != value["digest"]
        ):
            raise ContractError("BINDING_MISMATCH")
    elif name == "AudienceSnapshot":
        _unique(value["counts"], "release_group_id")
        ids = [row["release_group_id"] for row in value["counts"]]
        if (
            ids != sorted(ids)
            or any(row["listeners"] > value["sample_listeners"] for row in value["counts"])
            or value["dataset_id"] != value["recall_binding"]["data_snapshot_id"]
            or value["window"] != value["recall_binding"]["window"]
            or content_digest(value, "digest") != value["digest"]
        ):
            raise ContractError("BINDING_MISMATCH")
    elif name == "RoundStart":
        config = value["selection_config"]
        if (
            not config["recommendation_target_count"]
            <= config["shortlist_target_count"]
            <= config["recall_target_count"]
        ):
            raise ContractError("POLICY_OUT_OF_RANGE")
        if content_digest(value) != value["payload_digest"]:
            raise ContractError("BINDING_MISMATCH")
        policy = value["agent_policy"]
        if content_digest(policy, "digest") != policy["digest"]:
            raise ContractError("BINDING_MISMATCH")
        raw = value["input"]["raw"]
        accepted = value["input"]["accepted_preferences"]
        if raw is not None and (
            value["start_mode"] != "FRESH" or value["round_seq"] != 1 or accepted is not None
        ):
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
        if (
            sum(limits["lane_caps"].values()) > limits["round_cap_microusd"]
            or limits["round_cap_microusd"] > limits["session_cap_microusd"]
        ):
            raise ContractError("POLICY_OUT_OF_RANGE")
        if policy["writing"]["short_min_han"] > policy["writing"]["short_max_han"]:
            raise ContractError("POLICY_OUT_OF_RANGE")
        if (
            policy["concurrency"]["model_max_in_flight"] is not None
            and policy["concurrency"]["background_model_max_in_flight"]
            > policy["concurrency"]["model_max_in_flight"]
        ):
            raise ContractError("POLICY_OUT_OF_RANGE")
        if (
            policy["screening"]["reads_per_album_initial"]
            > policy["screening"]["max_reads_per_album"]
        ):
            raise ContractError("POLICY_OUT_OF_RANGE")
        if accepted is not None:
            _semantic("EffectivePreferenceSnapshot", accepted)
        if value["start_mode"] == "CONTINUE":
            continuation = value["continuation"]
            bundle = continuation["candidate_bundle"]
            expected = {
                "effective_preference_digest": accepted["effective_preference_digest"],
                "exclusion_digest": value["input"]["exclusion_digest"],
                "selection_config_revision": config["revision"],
                "agent_policy_revision": policy["revision"],
                "recall_binding": value["recall_binding"],
            }
            if (
                any(bundle["binding"][key] != item for key, item in expected.items())
                or bundle["digest"] != digest(bundle["accepted"])
                or bundle["binding"]["candidate_bundle_digest"] != bundle["digest"]
            ):
                raise ContractError("BINDING_MISMATCH")
            _unique(bundle["accepted"], "release_group_id")
            _unique(continuation["candidates"], "release_group_id")
            candidates = {
                item["release_group_id"]: item["recall_rank"] for item in bundle["accepted"]
            }
            if len(continuation["candidates"]) != len(candidates) or any(
                candidates.get(item["release_group_id"]) != item["recall_rank"]
                for item in continuation["candidates"]
            ):
                raise ContractError("BINDING_MISMATCH")
            display = continuation["display_state"]
            if not set(display["shown_candidate_ids"] + display["ready_unshown_ids"]).issubset(
                candidates
            ) or set(display["shown_candidate_ids"]) & set(display["ready_unshown_ids"]):
                raise ContractError("BINDING_MISMATCH")
    elif name in {"PreferenceResolutionProposal", "RecallProposal"}:
        if content_digest(value, "proposal_digest") != value["proposal_digest"]:
            raise ContractError("BINDING_MISMATCH")
        if name == "RecallProposal":
            _unique(value["candidates"], "release_group_id")
            if (
                value["returned_count"] != len(value["candidates"])
                or value["shortfall"] != value["requested_count"] - value["returned_count"]
            ):
                raise ContractError("COUNT_MISMATCH")
            ranks = [item["recall_rank"] for item in value["candidates"]]
            if ranks != list(range(1, len(ranks) + 1)):
                raise ContractError("RANK_MISMATCH")
        else:
            for item in value["preferences"] + value["constraints"]:
                if item["text_span"]["start"] >= item["text_span"]["end"]:
                    raise ContractError("INVALID_TEXT_SPAN")
    elif name == "EffectivePreferenceSnapshot":
        if (
            content_digest(value, "effective_preference_digest")
            != value["effective_preference_digest"]
        ):
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
            if value["binding"] is None and (
                value["payload"]["outcome"] == "DISPLAYABLE"
                or cards
                or value["payload"]["screenings"]
            ):
                raise ContractError("MISSING_BINDING")
            _unique(cards, "candidate_id")
            if value["payload"]["outcome"] == "DISPLAYABLE" and not cards:
                raise ContractError("EMPTY_DISPLAYABLE")
            if (
                value["payload"]["outcome"] != "DISPLAYABLE"
                and value["payload"]["reason_code"] is None
            ):
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
        if (
            any(value[k] is None for k in ("final_url", "publisher_key", "content_digest"))
            or value["text_chars"] == 0
        ):
            raise ContractError("INCOMPLETE_READ")


def _time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


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
