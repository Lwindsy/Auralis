"""Deterministic bounded excerpt, stamped by the read transport before registration."""

import re

_MUSIC = re.compile(
    r"guitar|drum|rhythm|melod|vocal|synth|production|piano|bass|arrang|texture|节奏|配器|人声|音色|编曲|制作|氛围|演奏|旋律|鼓",
    re.I,
)


def excerpt(text: str, byte_budget: int) -> str:
    paragraphs = [paragraph.strip() for paragraph in text.splitlines() if paragraph.strip()]
    if not paragraphs:
        return ""
    ranked = sorted(
        range(len(paragraphs)),
        key=lambda index: (index != 0, -len(_MUSIC.findall(paragraphs[index])), index),
    )
    chosen = {}
    remaining = byte_budget
    for index in ranked:
        encoded = paragraphs[index].encode("utf-8")
        if remaining <= 0:
            break
        piece = encoded[:remaining].decode("utf-8", errors="ignore").strip()
        if piece:
            chosen[index] = piece
            remaining -= len(piece.encode("utf-8")) + 1
    return "\n".join(chosen[index] for index in sorted(chosen))
