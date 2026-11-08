"""Exactly two model calls for text; all accepted snapshots come from control."""

from copy import deepcopy

from jsonschema import Draft202012Validator

from auralis_intelligence.agents import Execution
from auralis_intelligence.contracts import content_digest, validate_definition
from auralis_intelligence.providers import ModelGateway
from .prompts import PASS_ONE_PROMPT, PASS_TWO_PROMPT
from .reference import PreferenceError, ReferenceCatalog, locate
from .schemas import PASS_ONE, PASS_TWO

PARSER_REVISION = "preferences-v1-pass1-v0.6-pass2-v0.8"


class PreferencesStage:
    def __init__(self, gateway: ModelGateway, reference: ReferenceCatalog, *, genre_limit: int | None = None) -> None:
        self.gateway = gateway
        self.reference = reference
        if genre_limit is not None and genre_limit < 1:
            raise ValueError("GENRE_LIMIT_MUST_BE_POSITIVE")
        self.genre_limit = genre_limit

    async def _generate(self, execution: Execution, system: str, payload: dict, schema: dict, version: str) -> dict:
        result = await self.gateway.generate(execution, role="interpret", purpose="INTERPRET", system=system,
                                             input=payload, output_schema=schema, prompt_version=version)
        if result.outcome != "COMPLETED" or result.finish != "COMPLETE":
            raise PreferenceError("INTERPRETATION_FAILED")
        if next(Draft202012Validator(schema).iter_errors(result.output), None) is not None:
            raise PreferenceError("INTERPRETATION_INVALID_OUTPUT")
        return result.output

    async def __call__(self, execution: Execution) -> dict:
        with execution.clock.measure("interpret"):
            return await self._resolve(execution)

    async def _resolve(self, execution: Execution) -> dict:
        execution.ensure_active()
        accepted = execution.start["input"]["accepted_preferences"]
        if accepted is not None:
            validate_definition("EffectivePreferenceSnapshot", accepted)
            return deepcopy(accepted)
        raw = execution.start["input"]["raw"]
        validate_definition("RawInput", raw)
        text = raw["request_text"]
        proposal = {"input_digest": raw["input_digest"], "reference_revision": self.reference.revision,
                    "parser_revision": PARSER_REVISION, "preferences": [], "constraints": [],
                    "unresolved_constraints": [], "soft_preferences": []}
        if text.strip():
            execution.phase = "INTERPRETING"
            branches = self.reference.branch_material()
            first = await self._generate(execution, PASS_ONE_PROMPT, {"request_text": text, "branches": branches}, PASS_ONE, "genre_pass1_v0.6")
            ids = [row["id"] for row in first["branches"]]
            if len(set(ids)) != len(ids) or set(ids) - {row["id"] for row in branches}:
                raise PreferenceError("INTERPRETATION_INVALID_ID")
            for row in first["branches"]:
                locate(text, row["quote"])
            genres = (self.reference.expand(ids) if self.genre_limit is None
                      else self.reference.expand_limited(ids, self.genre_limit, text))
            second = await self._generate(execution, PASS_TWO_PROMPT, {"request_text": text, "genres": genres}, PASS_TWO, "genre_pass2_v0.8")
            if not any(second.values()):
                raise PreferenceError("INTERPRETATION_INVALID_OUTPUT")
            known = {row["id"] for row in genres}
            for key in ("preferences", "constraints"):
                seen = set()
                for row in second[key]:
                    identity = (row["id"], row["kind"])
                    if row["id"] not in known or identity in seen:
                        raise PreferenceError("INTERPRETATION_INVALID_ID")
                    seen.add(identity)
                    proposal[key].append({**{k: v for k, v in row.items() if k != "quote"}, "text_span": locate(text, row["quote"])})
            proposal["unresolved_constraints"] = [locate(text, quote) for quote in second["unresolved_constraints"]]
            for index, row in enumerate(second["soft_preferences"]):
                locate(text, row["quote"])
                proposal["soft_preferences"].append({"preference_id": f"text:{index + 1}",
                    **{key: row[key] for key in ("description", "kind", "basis")}, "strength": "normal", "focused": False})
            if proposal["unresolved_constraints"]:
                raise PreferenceError("UNSUPPORTED_CONSTRAINT", "NEEDS_INPUT", spans=tuple(proposal["unresolved_constraints"]))
            directions = {}
            for row in proposal["preferences"] + proposal["constraints"]:
                if row.get("basis", "explicit") != "explicit":
                    continue
                positive = row["kind"] in ("prefer", "require")
                if row["id"] in directions and directions[row["id"]] != positive:
                    raise PreferenceError("PREFERENCE_CONFLICT", "NEEDS_INPUT")
                directions[row["id"]] = positive
        proposal["proposal_digest"] = content_digest(proposal, "proposal_digest")
        validate_definition("PreferenceResolutionProposal", proposal)
        accepted = await execution.control_request("preference-resolution", proposal)
        validate_definition("EffectivePreferenceSnapshot", accepted)
        if any(accepted[key] != proposal[key] for key in ("input_digest", "reference_revision", "parser_revision")):
            raise PreferenceError("PREFERENCE_BINDING_MISMATCH")
        # The full raw text remains only in Execution until its existing cleanup.
        execution.start["input"]["accepted_preferences"] = deepcopy(accepted)
        return accepted
