"""Explicit local binding configuration; credentials are supplied separately."""

import json
from pathlib import Path

from auralisy_intelligence.contracts import validate_definition

from .types import ModelBinding


def load_bindings(path: str | Path) -> dict[str, ModelBinding]:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if not {"interpret", "screen", "select", "write"}.issubset(raw) or set(raw) - {
        "interpret",
        "screen",
        "select",
        "write",
        "long_write",
    }:
        raise ValueError("FOUR_MODEL_ROLES_REQUIRED")
    bindings = {}
    for value in raw.values():
        binding = ModelBinding(**value)
        validate_definition("ModelBinding", binding.wire())
        if binding.ref in bindings and bindings[binding.ref] != binding:
            raise ValueError("CONFLICTING_MODEL_BINDING")
        bindings[binding.ref] = binding
    return bindings
