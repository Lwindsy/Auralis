"""Deterministic AI-013 merge, injectable into JavaControlDouble only.

This function does not replace Java's authoritative identity and permission checks.
"""

from copy import deepcopy

from auralis_intelligence.contracts import content_digest, validate_definition

from .reference import PreferenceError, ReferenceCatalog


def merge_preferences(
    raw: dict,
    proposal: dict,
    *,
    reference: ReferenceCatalog,
    seed_descriptions: dict[str, str] | None = None,
) -> dict:
    validate_definition("RawInput", raw)
    validate_definition("PreferenceResolutionProposal", proposal)
    if (
        content_digest(raw, "input_digest") != raw["input_digest"]
        or proposal["input_digest"] != raw["input_digest"]
        or proposal["reference_revision"] != reference.revision
    ):
        raise PreferenceError("PREFERENCE_BINDING_MISMATCH")
    for span in proposal["unresolved_constraints"]:
        if not 0 <= span["start"] < span["end"] <= len(raw["request_text"]):
            raise PreferenceError("INTERPRETATION_INVALID_SPAN")
    liked_ids = [row["genre_id"] for row in raw["liked_genres"]]
    album_ids = [
        row["release_group_id"] or row["external"]["mb_release_group_id"]
        for row in raw["liked_albums"]
    ]
    if len(liked_ids) != len(set(liked_ids)) or len(album_ids) != len(set(album_ids)):
        raise PreferenceError("DUPLICATE_ID")
    if proposal["unresolved_constraints"]:
        raise PreferenceError("UNSUPPORTED_CONSTRAINT", "NEEDS_INPUT")
    tags = {
        row["genre_id"]: {
            "id": row["genre_id"],
            "strength": row["strength"],
            "focused": row["focused"],
        }
        for row in raw["liked_genres"]
    }
    disliked = dict.fromkeys(raw["disliked_genre_ids"])
    selected = set(tags) | set(disliked)
    seeds, soft, overrides = {}, [], []
    required, forbidden, directions = {}, {}, {}
    seed_descriptions = seed_descriptions or {}

    def add_soft(
        identifier: str,
        description: str,
        kind: str,
        basis: str = "explicit",
        strength: str = "normal",
        focused: bool = False,
    ) -> None:
        soft.append(
            {
                "preference_id": identifier,
                "description": description,
                "kind": kind,
                "basis": basis,
                "strength": strength,
                "focused": focused,
            }
        )

    def genre_name(identifier: str) -> str:
        if identifier not in reference.genres:
            raise PreferenceError("INPUT_DESCRIPTION_UNAVAILABLE")
        return reference.genres[identifier]["name"]

    for album in raw["liked_albums"]:
        identifier = album["release_group_id"] or album["external"]["mb_release_group_id"]
        description = seed_descriptions.get(identifier)
        if not description or not description.strip():
            raise PreferenceError("INPUT_DESCRIPTION_UNAVAILABLE")
        add_soft(
            "seed:" + str(len(soft)),
            "喜欢专辑：" + description,
            "prefer",
            strength=album["strength"],
            focused=album["focused"],
        )
        weighted = {"strength": album["strength"], "focused": album["focused"]}
        if album["release_group_id"]:
            seeds[identifier] = {"id": identifier, **weighted}
        else:
            for genre in album["external"]["mapped_genre_ids"]:
                genre_name(genre)
                if genre not in disliked:
                    tags.setdefault(genre, {"id": genre, **weighted})

    for row in proposal["preferences"] + proposal["constraints"]:
        identifier, kind = row["id"], row["kind"]
        genre_name(identifier)
        span = row["text_span"]
        if not 0 <= span["start"] < span["end"] <= len(raw["request_text"]):
            raise PreferenceError("INTERPRETATION_INVALID_SPAN")
        if row.get("basis", "explicit") == "explicit":
            positive = kind in ("prefer", "require")
            if identifier in directions and directions[identifier] != positive:
                raise PreferenceError("PREFERENCE_CONFLICT", "NEEDS_INPUT")
            directions[identifier] = positive

    # Explicit evidence wins over inference regardless of model array order.
    explicit_ids = {row["id"] for row in proposal["preferences"] if row["basis"] == "explicit"} | {
        row["id"] for row in proposal["constraints"]
    }
    for row in proposal["preferences"] + proposal["constraints"]:
        identifier, kind = row["id"], row["kind"]
        basis = row.get("basis", "explicit")
        if basis == "inferred" and (identifier in selected or identifier in explicit_ids):
            continue
        if basis == "explicit":
            overrides.append(
                {
                    "genre_id": identifier,
                    "action": kind.upper(),
                    "text_span": deepcopy(row["text_span"]),
                }
            )
        if kind in ("prefer", "require"):
            disliked.pop(identifier, None)
            tags.setdefault(identifier, {"id": identifier, "strength": "normal", "focused": False})
            if kind == "require":
                required[identifier] = None
        else:
            tags.pop(identifier, None)
            if kind == "exclude":
                forbidden[identifier] = None
            else:
                add_soft(
                    "genre-avoid:" + str(len(soft)),
                    "倾向避开风格：" + genre_name(identifier),
                    "avoid",
                    basis,
                )

    for identifier, row in tags.items():
        basis = (
            "inferred"
            if any(
                p["id"] == identifier and p["basis"] == "inferred" for p in proposal["preferences"]
            )
            and identifier not in selected | explicit_ids
            else "explicit"
        )
        add_soft(
            "genre:" + str(len(soft)),
            "喜欢风格：" + genre_name(identifier),
            "prefer",
            basis,
            row["strength"],
            row["focused"],
        )
    for identifier in disliked:
        add_soft("disliked:" + str(len(soft)), "预选不喜欢风格：" + genre_name(identifier), "avoid")
    for identifier in required:
        add_soft(
            "required:" + str(len(soft)), "每张必须包含风格：" + genre_name(identifier), "context"
        )
    for identifier in forbidden:
        add_soft(
            "forbidden:" + str(len(soft)),
            "排除含有风格的作品：" + genre_name(identifier),
            "context",
        )
    positive_roots = list(
        dict.fromkeys(
            row["id"]
            for row in proposal["preferences"] + proposal["constraints"]
            if row["kind"] in {"prefer", "require"} and row.get("basis", "explicit") == "explicit"
        )
    )
    if disliked and positive_roots:
        add_soft(
            "disliked-exceptions:" + str(len(soft)),
            "若以下本次明确喜欢的风格属于预选不喜欢项的子类，则该风格及其子类作为例外："
            + "、".join(genre_name(identifier) for identifier in positive_roots)
            + "。其他子类仍按原规则排除；独立的更具体预选和明确硬排除不因该例外放宽。",
            "context",
        )
    soft.extend(deepcopy(proposal["soft_preferences"]))
    snapshot = {
        "resolution_id": "resolution:" + proposal["proposal_digest"],
        "input_digest": raw["input_digest"],
        "reference_revision": reference.revision,
        "parser_revision": proposal["parser_revision"],
        "seeds": list(seeds.values()),
        "tags": list(tags.values()),
        "disliked_tags": list(disliked),
        "forbidden_tags": list(forbidden),
        "required_tags": list(required),
        "soft_preferences": soft,
        "overrides": overrides,
        "mode": raw["mode"],
        "has_positive_signal": bool(seeds or tags),
    }
    snapshot["effective_preference_digest"] = content_digest(
        snapshot, "effective_preference_digest"
    )
    validate_definition("EffectivePreferenceSnapshot", snapshot)
    return snapshot
