"""Interpret text in up to two passes; accepted snapshots always come from control."""

import re
from copy import deepcopy

from auralis_intelligence.agents import Execution
from auralis_intelligence.contracts import content_digest, validate_definition
from auralis_intelligence.providers import ModelGateway
from jsonschema import Draft202012Validator

from . import prompts, prompts_en
from .reference import PreferenceError, ReferenceCatalog, locate, numbered_choices
from .schemas import pass_one_schema, pass_two_schema

# The authority revision identifies the normalized proposal. Prompt versions track model I/O.
PARSER_REVISION = "preferences-v1-pass1-v0.7-pass2-v0.8"
SAME_SOUND_ONLY = re.compile(
    r"(?:(?:我)?(?:就|只)?(?:想|要)(?:听)?|(?:请)?(?:给我)?(?:来|推荐)(?:点|一点|一些|几首|几张)?)?"
    r"(?:听感|氛围)(?:完全)?(?:一样|一致|相同|相似|类似)(?:的(?:歌|音乐|专辑)?)?[。！？.!?]*"
    r"|(?:(?:i (?:just )?want|give me|recommend) )?(?:something with )?(?:the )?"
    r"(?:exact same|same|similar) (?:vibe|feel|mood|atmosphere|sound)[.!?]*",
    re.IGNORECASE,
)


def _text_preference(identifier: str, description: str, kind: str) -> dict:
    return {
        "preference_id": identifier,
        "description": description,
        "kind": kind,
        "basis": "explicit",
        "strength": "normal",
        "focused": False,
    }


class PreferencesStage:
    def __init__(
        self, gateway: ModelGateway, reference: ReferenceCatalog, *, genre_limit: int | None = None
    ) -> None:
        self.gateway = gateway
        self.reference = reference
        if genre_limit is not None and genre_limit < 1:
            raise ValueError("GENRE_LIMIT_MUST_BE_POSITIVE")
        self.genre_limit = genre_limit

    async def _generate(
        self, execution: Execution, system: str, payload: dict, schema: dict, version: str
    ) -> dict:
        result = await self.gateway.generate(
            execution,
            role="interpret",
            purpose="INTERPRET",
            system=system,
            input=payload,
            output_schema=schema,
            prompt_version=version + (".en" if execution.english else ""),
        )
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
        proposal = {
            "input_digest": raw["input_digest"],
            "reference_revision": self.reference.revision,
            "parser_revision": PARSER_REVISION,
            "preferences": [],
            "constraints": [],
            "unresolved_constraints": [],
            "soft_preferences": [],
        }
        if SAME_SOUND_ONLY.fullmatch(text.strip()):
            proposal["soft_preferences"].append(
                _text_preference("text:similar-sound", text, "prefer")
            )
            return await self._accept(execution, proposal)
        if text.strip():
            edition = prompts_en if execution.english else prompts
            execution.phase = "INTERPRETING"
            branches, branch_ids = numbered_choices(self.reference.branch_material())
            first = await self._generate(
                execution,
                edition.PASS_ONE_PROMPT,
                {"request_text": text, "branches": branches},
                pass_one_schema(len(branches)),
                "genre_pass1_v0.9",
            )
            ids = [branch_ids[row["number"]] for row in first["branches"]]
            if len(set(ids)) != len(ids):
                raise PreferenceError("INTERPRETATION_INVALID_ID")
            for row in first["branches"]:
                locate(text, row["quote"])
            if not ids:
                # A model's empty answer cannot discard a named genre from this reference.
                folded = text.casefold()
                for genre in self.reference.genres.values():
                    for label in [genre["name"], *genre["aliases"]]:
                        label = label.strip().casefold()
                        if not label:
                            continue
                        mentioned = (
                            label in folded
                            if any("\u4e00" <= char <= "\u9fff" for char in label)
                            else re.search(r"(?<!\w)" + re.escape(label) + r"(?!\w)", folded)
                        )
                        if mentioned:
                            raise PreferenceError("INTERPRETATION_INVALID_OUTPUT")
                proposal["soft_preferences"].append(
                    _text_preference("text:open-request", text, "context")
                )
                return await self._accept(execution, proposal)
            genres = (
                self.reference.expand(ids)
                if self.genre_limit is None
                else self.reference.expand_limited(ids, self.genre_limit, text)
            )
            choices, genre_ids = numbered_choices(genres)
            second = await self._generate(
                execution,
                edition.PASS_TWO_PROMPT,
                {"request_text": text, "genres": choices},
                pass_two_schema(len(choices)),
                "genre_pass2_v1.0",
            )
            if not any(second.values()):
                raise PreferenceError("INTERPRETATION_INVALID_OUTPUT")
            for key in ("preferences", "constraints"):
                seen = set()
                for row in second[key]:
                    identifier = genre_ids[row["number"]]
                    identity = (identifier, row["kind"])
                    if identity in seen:
                        raise PreferenceError("INTERPRETATION_INVALID_ID")
                    seen.add(identity)
                    proposal[key].append(
                        {
                            **{k: v for k, v in row.items() if k not in {"number", "quote"}},
                            "id": identifier,
                            "text_span": locate(text, row["quote"]),
                        }
                    )
            proposal["unresolved_constraints"] = [
                locate(text, quote) for quote in second["unresolved_constraints"]
            ]
            self._add_named_genres(text, proposal, second["named_genres"])
            for index, row in enumerate(second["soft_preferences"]):
                locate(text, row["quote"])
                proposal["soft_preferences"].append(
                    {
                        "preference_id": f"text:{index + 1}",
                        **{key: row[key] for key in ("description", "kind", "basis")},
                        "strength": "normal",
                        "focused": False,
                    }
                )
            if proposal["unresolved_constraints"]:
                raise PreferenceError(
                    "UNSUPPORTED_CONSTRAINT",
                    "NEEDS_INPUT",
                    spans=tuple(proposal["unresolved_constraints"]),
                )
            directions = {}
            for row in proposal["preferences"] + proposal["constraints"]:
                if row.get("basis", "explicit") != "explicit":
                    continue
                positive = row["kind"] in ("prefer", "require")
                if row["id"] in directions and directions[row["id"]] != positive:
                    raise PreferenceError("PREFERENCE_CONFLICT", "NEEDS_INPUT")
                directions[row["id"]] = positive
        return await self._accept(execution, proposal)

    def _add_named_genres(self, text: str, proposal: dict, rows: list[dict]) -> None:
        for index, row in enumerate(rows):
            quote, kind = row["quote"], row["kind"]
            span = locate(text, quote)
            identifier = self.reference.named_genre_id(row["name"])
            hard = kind in {"require", "exclude"}
            if identifier is None:
                if hard:
                    proposal["unresolved_constraints"].append(span)
                else:
                    proposal["soft_preferences"].append(
                        _text_preference(f"text:named-genre:{index + 1}", quote, kind)
                    )
                continue
            key = "constraints" if hard else "preferences"
            target = next(
                (
                    item
                    for item in proposal[key]
                    if item["id"] == identifier and item["kind"] == kind
                ),
                None,
            )
            if target is None:
                target = {"id": identifier, "kind": kind}
                proposal[key].append(target)
            target["text_span"] = span
            if not hard:
                target["basis"] = "explicit"

    async def _accept(self, execution: Execution, proposal: dict) -> dict:
        proposal["proposal_digest"] = content_digest(proposal, "proposal_digest")
        validate_definition("PreferenceResolutionProposal", proposal)
        accepted = await execution.control_request("preference-resolution", proposal)
        validate_definition("EffectivePreferenceSnapshot", accepted)
        if any(
            accepted[key] != proposal[key]
            for key in ("input_digest", "reference_revision", "parser_revision")
        ):
            raise PreferenceError("PREFERENCE_BINDING_MISMATCH")
        # The full raw text remains only in Execution until its existing cleanup.
        execution.start["input"]["accepted_preferences"] = deepcopy(accepted)
        return accepted
