"""Readable, execution-bound preference context for screening and writing."""

from typing import Any, Mapping

from auralis_intelligence.agents import Execution
from auralis_intelligence.contracts import ContractError, validate_definition

from .prompts_en import COMPLETE_REQUEST_DESCRIPTION


def preference_context(
    execution: Execution,
    snapshot: dict[str, Any],
    *,
    genre_names: Mapping[str, str] | None = None,
    album_names: Mapping[str, str] | None = None,
) -> tuple[dict[str, Any], dict[str, str]]:
    validate_definition("EffectivePreferenceSnapshot", snapshot)
    if (
        execution.binding is not None
        and snapshot["effective_preference_digest"]
        != execution.binding["effective_preference_digest"]
    ):
        raise ContractError("BINDING_MISMATCH")
    items = []
    aliases = {}
    for preference in snapshot["soft_preferences"]:
        alias = f"P{len(items) + 1}"
        aliases[alias] = preference["preference_id"]
        items.append(
            {
                "ref": alias,
                **{key: value for key, value in preference.items() if key != "preference_id"},
            }
        )
    for kind, values, labels in (
        ("genre", snapshot["tags"], genre_names or {}),
        ("album", snapshot["seeds"], album_names or {}),
    ):
        for preference in values:
            alias = f"P{len(items) + 1}"
            aliases[alias] = preference["id"]
            items.append(
                {
                    "ref": alias,
                    "kind": kind,
                    "name": labels.get(preference["id"]),
                    "name_known": preference["id"] in labels,
                    "strength": preference["strength"],
                    "focused": preference["focused"],
                    "basis": "explicit",
                }
            )
    raw = execution.start["input"].get("raw")
    text = raw["request_text"] if raw is not None else None
    if text:
        aliases["P0"] = "request_text"
        items.insert(
            0,
            {
                "ref": "P0",
                "kind": "complete_request",
                "basis": "explicit",
                "description": COMPLETE_REQUEST_DESCRIPTION
                if execution.english
                else "完整诉求见request_text，包含未压缩的听感、情绪、场景、许可与避让；不要只看风格。",
            },
        )
    return {"mode": snapshot["mode"], "request_text": text, "preferences": items}, aliases
