"""Deterministic form and citation validation; not a music-quality judge."""

import re
from typing import Any

from auralis_intelligence.contracts import ContractError


SECTIONS = ("production", "instrumentation", "vocals", "mood")


def han_count(text: str) -> int:
    return len(re.findall(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\U00020000-\U0002fa1f]", text))


def citation_refs(text: str) -> set[str]:
    return set(re.findall(r"\[([A-Za-z][0-9]+)\]", text))


def validate_short(output: Any, available: set[str], policy: dict[str, Any]) -> None:
    if not isinstance(output, dict) or set(output) != {"text", "refs"} or not isinstance(output["text"], str) or not isinstance(output["refs"], list):
        raise ContractError("SHORT_STRUCTURE_INVALID")
    refs = output["refs"]
    if any(not isinstance(ref, str) for ref in refs) or len(set(refs)) != len(refs) or not refs or set(refs) != citation_refs(output["text"]) or not set(refs).issubset(available):
        raise ContractError("CITATION_INVALID")
    if not policy["short_min_han"] <= han_count(output["text"]) <= policy["short_max_han"]:
        raise ContractError("LENGTH_INVALID")
    without_markers = re.sub(r"\[[A-Za-z][0-9]+\]", "", output["text"])
    sentences = [part.strip() for part in re.split(r"[。！？!?]+", without_markers) if part.strip()]
    if not 1 <= len(sentences) <= 3 or re.search(r"https?://|www\.", output["text"], re.I):
        raise ContractError("SHORT_FORM_INVALID")


def validate_long(output: Any, available: set[str], policy: dict[str, Any]) -> None:
    if not isinstance(output, dict) or output.get("status") not in {"ok", "needs_more_research"} or set(output.get("sections", {})) != set(SECTIONS):
        raise ContractError("LONG_STRUCTURE_INVALID")
    refs = output.get("refs")
    if not isinstance(refs, list) or any(not isinstance(ref, str) for ref in refs) or len(refs) != len(set(refs)) or not set(refs).issubset(available):
        raise ContractError("CITATION_INVALID")
    if any(not isinstance(text, str) for text in output["sections"].values()):
        raise ContractError("LONG_STRUCTURE_INVALID")
    mentioned = set().union(*(citation_refs(text) for text in output["sections"].values()))
    if mentioned != set(refs):
        raise ContractError("CITATION_INVALID")
    if output["status"] == "needs_more_research":
        if not output.get("missing") or len(output["missing"]) != len(set(output["missing"])) or not set(output["missing"]).issubset(SECTIONS):
            raise ContractError("LONG_MISSING_SECTIONS_INVALID")
        return
    sections = output["sections"]
    if any(not isinstance(text, str) for text in sections.values()) or output.get("missing") or output.get("tool_requests"):
        raise ContractError("LONG_STRUCTURE_INVALID")
    if not refs:
        raise ContractError("CITATION_INVALID")
    used = set()
    for text in sections.values():
        section_refs = citation_refs(text)
        if (text.strip() and not section_refs) or not section_refs.issubset(available) or re.search(r"https?://|www\.", text, re.I):
            raise ContractError("CITATION_INVALID")
        used.update(section_refs)
    if used != set(refs):
        raise ContractError("CITATION_INVALID")
