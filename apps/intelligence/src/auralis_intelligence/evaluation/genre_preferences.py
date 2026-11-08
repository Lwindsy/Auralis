"""AI-023 pilot runner for the frozen two-pass preference evaluation."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import tempfile
import time
from copy import deepcopy
from dataclasses import asdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from auralis_intelligence.agents import CallDenied, Execution
from auralis_intelligence.agents.control_double import JavaControlDouble
from auralis_intelligence.agents.demo import DemoHarness
from auralis_intelligence.contracts import ContractError, content_digest, digest
from auralis_intelligence.preferences import (
    PreferenceError,
    PreferencesStage,
    ReferenceCatalog,
    merge_preferences,
)
from auralis_intelligence.preferences.prompts import PASS_ONE_PROMPT, PASS_TWO_PROMPT
from auralis_intelligence.preferences.schemas import PASS_ONE, PASS_TWO
from auralis_intelligence.providers import ModelGateway, OpenAIResponsesAdapter
from auralis_intelligence.providers.config import load_bindings

MANIFEST_KEYS = {
    "run_id",
    "dataset",
    "dataset_sha256",
    "reference",
    "reference_sha256",
    "model_bindings",
    "case_ids",
    "second_pass_genre_limit",
    "trial_budget_microusd",
    "per_case_budget_microusd",
    "provider_key_env",
    "output_dir",
}
OPTIONAL_MANIFEST_KEYS = {"expected_pass1_branches"}


def _sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _resolve(value: str) -> Path:
    return Path(value).resolve()


def load_manifest(path: str | Path) -> dict[str, Any]:
    manifest = _read_json(Path(path))
    if (
        not isinstance(manifest, dict)
        or not MANIFEST_KEYS.issubset(manifest)
        or set(manifest) - MANIFEST_KEYS - OPTIONAL_MANIFEST_KEYS
    ):
        raise ValueError("INVALID_EVALUATION_MANIFEST")
    if (
        not isinstance(manifest["case_ids"], list)
        or not manifest["case_ids"]
        or len(set(manifest["case_ids"])) != len(manifest["case_ids"])
    ):
        raise ValueError("INVALID_CASE_IDS")
    for key in ("second_pass_genre_limit", "trial_budget_microusd", "per_case_budget_microusd"):
        if type(manifest[key]) is not int or manifest[key] < 1:
            raise ValueError("INVALID_EVALUATION_LIMIT")
    if manifest["per_case_budget_microusd"] > manifest["trial_budget_microusd"]:
        raise ValueError("CASE_BUDGET_EXCEEDS_TRIAL_BUDGET")
    expected = manifest.get("expected_pass1_branches", {})
    if (
        not isinstance(expected, dict)
        or set(expected) - set(manifest["case_ids"])
        or any(
            not isinstance(rows, list)
            or not 1 <= len(rows) <= 3
            or len(rows) != len(set(rows))
            or any(not isinstance(row, str) for row in rows)
            for rows in expected.values()
        )
    ):
        raise ValueError("INVALID_EXPECTED_PASS1_BRANCHES")
    return manifest


def _case_ids(manifest: dict[str, Any], dataset: dict[str, Any]) -> list[str]:
    return (
        [row["id"] for row in dataset["questions"]]
        if manifest["case_ids"] == ["*"]
        else manifest["case_ids"]
    )


def _binding_rows(path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    raw = _read_json(path)
    loaded = load_bindings(path)
    return raw, {role: loaded[value["ref"]] for role, value in raw.items()}


def _reservation(binding: Any, input_bound: int, *, search: bool = False) -> int | None:
    prices = (binding.input_microusd_per_million, binding.output_microusd_per_million)
    if any(value is None for value in prices):
        return None
    amount = input_bound * prices[0] + binding.max_output_tokens * prices[1]
    search_cost = binding.search_microusd_per_call if search else 0
    if search_cost is None:
        return None
    return search_cost + (amount + 999_999) // 1_000_000


def _input_bound(system: str, payload: Any, schema: dict[str, Any]) -> int:
    material = {"system": system, "input": payload, "schema": schema}
    return len(json.dumps(material, ensure_ascii=False).encode("utf-8")) + 256


def _annotated_planning_branches(
    question: dict[str, Any], reference: ReferenceCatalog
) -> list[str] | None:
    """Use frozen reviewer annotations only for preflight sizing, never model input."""
    labels = []
    for direction in question.get("directions", []):
        labels.extend(row.get("genre") for row in direction.get("acceptable", []))
    for constraint in question.get("constraints", []):
        labels.extend(constraint.get("genres", []))
    wanted = set()
    for label in labels:
        if not isinstance(label, str):
            continue
        normalized = label.casefold()
        for identifier, row in reference.genres.items():
            if normalized in {
                row["name"].casefold(),
                *(alias.casefold() for alias in row["aliases"]),
            }:
                wanted.add(identifier)
    if not wanted:
        return None
    selected = []
    uncovered = set(wanted)
    while uncovered and len(selected) < 3:
        branch = max(
            reference.branches,
            key=lambda identifier: len(
                uncovered & set(reference.branches[identifier]["genre_ids"])
            ),
        )
        covered = uncovered & set(reference.branches[branch]["genre_ids"])
        if not covered:
            break
        selected.append(branch)
        uncovered -= covered
    return selected or None


def build_plan(manifest_path: str | Path) -> dict[str, Any]:
    manifest = load_manifest(manifest_path)
    dataset_path, reference_path, bindings_path = map(
        _resolve, (manifest["dataset"], manifest["reference"], manifest["model_bindings"])
    )
    actual_hashes = {"dataset": _sha256(dataset_path), "reference": _sha256(reference_path)}
    if actual_hashes != {
        "dataset": manifest["dataset_sha256"],
        "reference": manifest["reference_sha256"],
    }:
        raise ValueError("EVALUATION_INPUT_HASH_MISMATCH")
    dataset = _read_json(dataset_path)
    if (
        dataset.get("split") not in {"development", "holdout"}
        or dataset.get("status") != "frozen"
        or not isinstance(dataset.get("questions"), list)
    ):
        raise ValueError("FROZEN_EVALUATION_DATASET_REQUIRED")
    questions = {item["id"]: item for item in dataset["questions"]}
    case_ids = _case_ids(manifest, dataset)
    if any(identifier not in questions for identifier in case_ids):
        raise ValueError("UNKNOWN_CASE_ID")
    reference = ReferenceCatalog.load(reference_path)
    _, bindings = _binding_rows(bindings_path)
    binding = bindings["interpret"]
    branches = reference.branch_material()
    maximum = 0
    estimable = True
    inputs_fit = True
    cases_fit = True
    maximum_first_input_bound = 0
    maximum_second_input_bound = 0
    per_case = []
    for identifier in case_ids:
        text = questions[identifier]["request_text"]
        first_bound = _input_bound(
            PASS_ONE_PROMPT, {"request_text": text, "branches": branches}, PASS_ONE
        )
        maximum_first_input_bound = max(maximum_first_input_bound, first_bound)
        first = _reservation(binding, first_bound)
        expected_branches = manifest.get("expected_pass1_branches", {}).get(identifier)
        if expected_branches is None:
            expected_branches = _annotated_planning_branches(questions[identifier], reference)
        if expected_branches is None:
            sample_genres = sorted(
                reference.genres.values(),
                key=lambda row: len(
                    json.dumps(reference.material(row), ensure_ascii=False).encode("utf-8")
                ),
                reverse=True,
            )[: manifest["second_pass_genre_limit"]]
        else:
            if set(expected_branches) - set(reference.branches):
                raise ValueError("UNKNOWN_EXPECTED_BRANCH_ID")
            sample_genres = reference.expand_limited(
                expected_branches, manifest["second_pass_genre_limit"], text
            )
        second_payload = {
            "request_text": text,
            "genres": [reference.material(row) for row in sample_genres],
        }
        second_bound = _input_bound(PASS_TWO_PROMPT, second_payload, PASS_TWO)
        maximum_second_input_bound = max(maximum_second_input_bound, second_bound)
        second = _reservation(binding, second_bound)
        estimate = None if first is None or second is None else first + second
        estimable &= estimate is not None
        inputs_fit &= (
            first_bound <= binding.max_input_tokens and second_bound <= binding.max_input_tokens
        )
        cases_fit &= estimate is not None and estimate <= manifest["per_case_budget_microusd"]
        maximum += estimate or 0
        per_case.append(
            {
                "id": identifier,
                "language": questions[identifier]["language"],
                "evaluation_scope": questions[identifier]["evaluation_scope"],
                "reservation_microusd": estimate,
            }
        )
    live_ready = (
        binding.live_enabled
        and binding.model not in {"", "SET_AFTER_AI_023"}
        and bool(binding.price_source)
        and estimable
        and inputs_fit
        and cases_fit
    )
    return {
        "run_id": manifest["run_id"],
        "paid_requests": False,
        "split": dataset["split"],
        "dataset_sha256": actual_hashes["dataset"],
        "reference_sha256": actual_hashes["reference"],
        "case_count": len(per_case),
        "cases": per_case,
        "second_pass_genre_limit": manifest["second_pass_genre_limit"],
        "expected_pass1_branches": manifest.get("expected_pass1_branches"),
        "model": binding.model,
        "binding_ref": binding.ref,
        "price_revision": binding.price_revision,
        "price_source": binding.price_source,
        "maximum_first_input_bound": maximum_first_input_bound,
        "maximum_second_input_bound": maximum_second_input_bound,
        "estimated_maximum_microusd": maximum if estimable else None,
        "trial_budget_microusd": manifest["trial_budget_microusd"],
        "per_case_budget_microusd": manifest["per_case_budget_microusd"],
        "inputs_within_binding": inputs_fit,
        "cases_within_budget": cases_fit,
        "within_configured_budget": estimable
        and cases_fit
        and maximum <= manifest["trial_budget_microusd"],
        "live_ready": live_ready,
    }


class RecordingAdapter:
    def __init__(self, adapter: OpenAIResponsesAdapter) -> None:
        self.adapter = adapter
        self.records: list[dict[str, Any]] = []

    def check_binding(self, binding, *, search: bool = False) -> None:
        self.adapter.check_binding(binding, search=search)

    async def complete(self, binding, request):
        started = time.perf_counter()
        result = await self.adapter.complete(binding, request)
        self.records.append(
            {
                "prompt_version": request.schema_name,
                "model": binding.model,
                "input": deepcopy(request.input),
                "output": deepcopy(result.output),
                "outcome": result.outcome,
                "finish": result.finish,
                "usage": asdict(result.usage),
                "provider_request_ref": result.provider_request_ref,
                "elapsed_ms": round((time.perf_counter() - started) * 1000),
            }
        )
        return result


def _pricing(bindings: dict[str, Any]) -> dict[str, dict[str, int]]:
    result = {}
    for binding in bindings.values():
        values = {
            name: getattr(binding, name)
            for name in (
                "input_microusd_per_million",
                "cached_input_microusd_per_million",
                "output_microusd_per_million",
                "search_microusd_per_call",
            )
        }
        if any(value is None for value in values.values()):
            raise ValueError("INCOMPLETE_LIVE_PRICING")
        result[binding.ref] = values
    return result


def _case_start(
    template: dict[str, Any],
    manifest: dict[str, Any],
    bindings: dict[str, Any],
    question: dict[str, Any],
    index: int,
) -> dict[str, Any]:
    start = deepcopy(template)
    run_id = manifest["run_id"]
    suffix = f"{index + 1:03d}-{question['id']}"
    start.update(
        execution_id=f"{run_id}:{suffix}",
        session_id=f"{run_id}:session:{suffix}",
        round_id=f"{run_id}:round:{suffix}",
        run_id=f"{run_id}:run:{suffix}",
    )
    start["boot"] = {"python_boot_id": f"{run_id}:python", "coordinator_boot_id": f"{run_id}:java"}
    raw = start["input"]["raw"]
    raw.update(
        liked_genres=[],
        disliked_genre_ids=[],
        liked_albums=[],
        mode="explore",
        request_text=question["request_text"],
        language=question["language"],
    )
    raw["input_digest"] = content_digest(raw, "input_digest")
    start["input"].update(accepted_preferences=None, exclusions=[], exclusion_digest=digest([]))
    start["model_bindings"] = {role: binding.wire() for role, binding in bindings.items()}
    budget = start["budget"]
    # Every frozen case is an independent product session. The manifest owns the
    # cross-session batch cap; product session and round limits remain legal.
    budget.update(
        session_budget_scope_id=f"{run_id}:session-budget:{suffix}",
        round_budget_scope_id=f"{run_id}:round-budget:{suffix}",
    )
    budget["limits"].update(
        session_cap_microusd=manifest["per_case_budget_microusd"],
        round_cap_microusd=manifest["per_case_budget_microusd"],
        lane_caps={"FOREGROUND": manifest["per_case_budget_microusd"], "BACKGROUND": 0},
        model_calls=4,
        search_calls=1,
        read_calls=1,
    )
    budget["remaining_at_issue"] = {
        "session": manifest["per_case_budget_microusd"],
        "round": manifest["per_case_budget_microusd"],
        "FOREGROUND": manifest["per_case_budget_microusd"],
        "BACKGROUND": 0,
    }
    start["deadlines"]["round_deadline_at"] = (
        (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat().replace("+00:00", "Z")
    )
    start["payload_digest"] = content_digest(start)
    return start


async def run_live(manifest_path: str | Path, acknowledged_budget: int) -> Path:
    manifest = load_manifest(manifest_path)
    plan = build_plan(manifest_path)
    if acknowledged_budget != manifest["trial_budget_microusd"]:
        raise ValueError("BUDGET_ACKNOWLEDGEMENT_MISMATCH")
    if not plan["live_ready"] or not plan["within_configured_budget"]:
        raise ValueError("LIVE_PLAN_NOT_READY")
    api_key = os.environ.get(manifest["provider_key_env"], "")
    if not api_key:
        raise ValueError("MISSING_API_KEY")
    dataset = _read_json(_resolve(manifest["dataset"]))
    questions = {item["id"]: item for item in dataset["questions"]}
    case_ids = _case_ids(manifest, dataset)
    reference = ReferenceCatalog.load(_resolve(manifest["reference"]))
    _, bindings = _binding_rows(_resolve(manifest["model_bindings"]))
    output_dir = _resolve(manifest["output_dir"])
    if output_dir.exists():
        raise FileExistsError("EVALUATION_OUTPUT_ALREADY_EXISTS")
    output_dir.mkdir(parents=True)
    control = JavaControlDouble(f"{manifest['run_id']}:java", pricing_by_binding=_pricing(bindings))
    control.preference_resolver = lambda raw, proposal: merge_preferences(
        raw, proposal, reference=reference, seed_descriptions={}
    )
    records = []
    async with OpenAIResponsesAdapter(api_key) as provider:
        recording = RecordingAdapter(provider)
        gateway = ModelGateway(recording, {binding.ref: binding for binding in bindings.values()})
        stage = PreferencesStage(
            gateway, reference, genre_limit=manifest["second_pass_genre_limit"]
        )
        with tempfile.TemporaryDirectory(prefix="auralis-ai-023-") as directory:
            template_harness = DemoHarness(scenario="text", r=3, k=2, n=1, root=Path(directory))
            try:
                for index, identifier in enumerate(case_ids):
                    question = questions[identifier]
                    start = _case_start(
                        template_harness.request, manifest, bindings, question, index
                    )
                    control.register(start)
                    execution = Execution(start, control)
                    before = len(recording.records)
                    started = time.perf_counter()
                    accepted = None
                    error = None
                    try:
                        accepted = await stage(execution)
                    except (CallDenied, ContractError, PreferenceError) as exc:
                        error = getattr(exc, "code", type(exc).__name__)
                    invocations = [
                        item
                        for item in control.invocations.values()
                        if item["execution_id"] == execution.id
                    ]
                    case_calls = deepcopy(recording.records[before:])
                    model_status = (
                        "COMPLETED"
                        if len(case_calls) == 2
                        and all(
                            call["outcome"] == "COMPLETED" and call["finish"] == "COMPLETE"
                            for call in case_calls
                        )
                        else "FAILED"
                    )
                    resolution_status = (
                        "ACCEPTED"
                        if accepted is not None
                        else "NEEDS_INPUT"
                        if error == "UNSUPPORTED_CONSTRAINT"
                        else "FAILED"
                    )
                    records.append(
                        {
                            "id": identifier,
                            "family": question["family"],
                            "language": question["language"],
                            "evaluation_scope": question["evaluation_scope"],
                            "coverage": question["coverage"],
                            "request_text": question["request_text"],
                            "model_status": model_status,
                            "resolution_status": resolution_status,
                            "error": error,
                            "elapsed_ms": round((time.perf_counter() - started) * 1000),
                            "calls": case_calls,
                            "accepted_preferences": accepted,
                            "settled_microusd": sum(item.get("charge", 0) for item in invocations),
                            "reserved_microusd": sum(
                                item["reserved"] for item in invocations if not item["settled"]
                            ),
                        }
                    )
                    control.close_execution(execution.id)
            finally:
                template_harness.close()
    result = {
        "run_id": manifest["run_id"],
        "created_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "paid_requests": True,
        "plan": plan,
        "records": records,
        "summary": {
            "cases": len(records),
            "model_completed": sum(row["model_status"] == "COMPLETED" for row in records),
            "model_failed": sum(row["model_status"] == "FAILED" for row in records),
            "accepted": sum(row["resolution_status"] == "ACCEPTED" for row in records),
            "needs_input": sum(row["resolution_status"] == "NEEDS_INPUT" for row in records),
            "settled_microusd": sum(row["settled_microusd"] for row in records),
            "reserved_microusd": sum(row["reserved_microusd"] for row in records),
        },
    }
    target = output_dir / "run.json"
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description="AI-023 frozen two-pass preference pilot")
    subcommands = parser.add_subparsers(dest="command", required=True)
    prepare = subcommands.add_parser(
        "prepare", help="validate and price the plan without network or paid calls"
    )
    prepare.add_argument("--manifest", required=True)
    execute = subcommands.add_parser("run", help="execute the explicitly acknowledged paid plan")
    execute.add_argument("--manifest", required=True)
    execute.add_argument("--acknowledge-budget-microusd", required=True, type=int)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            print(json.dumps(build_plan(args.manifest), ensure_ascii=False, indent=2))
            return 0
        print(asyncio.run(run_live(args.manifest, args.acknowledge_budget_microusd)))
        return 0
    except (OSError, ValueError) as exc:
        parser.exit(2, str(exc) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
