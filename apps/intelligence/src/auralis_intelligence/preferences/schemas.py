"""Model-local output schemas; wire schemas remain owned by contracts."""


def object_schema(properties: dict) -> dict:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


def array(item: dict, *, minimum: int = 0, maximum: int = 1000) -> dict:
    return {"type": "array", "items": item, "minItems": minimum, "maxItems": maximum}


TEXT = {"type": "string", "minLength": 1, "maxLength": 4096}
BASIS = {"type": "string", "enum": ["explicit", "inferred"]}


def pass_one_schema(choice_count: int) -> dict:
    number = {"type": "integer", "minimum": 1, "maximum": choice_count}
    return object_schema(
        {"branches": array(object_schema({"number": number, "quote": TEXT}), maximum=3)}
    )


def pass_two_schema(choice_count: int) -> dict:
    number = {"type": "integer", "minimum": 1, "maximum": max(1, choice_count)}
    max_selections = min(choice_count * 2, 1000)
    return object_schema(
        {
            "preferences": array(
                object_schema(
                    {
                        "number": number,
                        "kind": {"enum": ["prefer", "avoid"], "type": "string"},
                        "basis": BASIS,
                        "quote": TEXT,
                    }
                ),
                maximum=max_selections,
            ),
            "constraints": array(
                object_schema(
                    {
                        "number": number,
                        "kind": {"enum": ["require", "exclude"], "type": "string"},
                        "quote": TEXT,
                    }
                ),
                maximum=max_selections,
            ),
            "named_genres": array(
                object_schema(
                    {
                        "name": {"type": "string", "minLength": 1, "maxLength": 160},
                        "kind": {
                            "enum": ["prefer", "avoid", "require", "exclude"],
                            "type": "string",
                        },
                        "quote": TEXT,
                    }
                )
            ),
            "unresolved_constraints": array(TEXT),
            "soft_preferences": array(
                object_schema(
                    {
                        "description": TEXT,
                        "kind": {
                            "enum": ["prefer", "avoid", "context", "permission"],
                            "type": "string",
                        },
                        "basis": BASIS,
                        "quote": TEXT,
                    }
                )
            ),
        }
    )
