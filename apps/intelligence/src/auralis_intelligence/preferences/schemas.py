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
ID = {"type": "string", "minLength": 1, "maxLength": 160}
BASIS = {"type": "string", "enum": ["explicit", "inferred"]}
PASS_ONE = object_schema(
    {"branches": array(object_schema({"id": ID, "quote": TEXT}), minimum=1, maximum=3)}
)
PASS_TWO = object_schema(
    {
        "preferences": array(
            object_schema(
                {
                    "id": ID,
                    "kind": {"enum": ["prefer", "avoid"], "type": "string"},
                    "basis": BASIS,
                    "quote": TEXT,
                }
            )
        ),
        "constraints": array(
            object_schema(
                {
                    "id": ID,
                    "kind": {"enum": ["require", "exclude"], "type": "string"},
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
