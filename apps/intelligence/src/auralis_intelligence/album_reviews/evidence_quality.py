"""Cheap page rejection only; semantic sufficiency remains a model decision."""

import re
import unicodedata


def _words(text):
    folded = unicodedata.normalize("NFKD", text.casefold())
    return " ".join(
        re.findall(r"[^\W_]+", "".join(c for c in folded if not unicodedata.combining(c)))
    )


def page_rejection(text, identity):
    """Return a technical reason, never a claim that the page is sufficient."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return "EMPTY_PAGE"
    headings = {
        "access denied",
        "just a moment",
        "verify you are human",
        "sign in to continue",
        "log in to continue",
        "请登录后继续",
        "访问被拒绝",
    }
    if _words(lines[0]) in headings:
        return "ACCESS_PAGE"
    if len(" ".join(text.split())) < 300:
        return "TEXT_TOO_SHORT"
    body = " " + _words(text) + " "
    for field, reason in (("title", "TITLE_NOT_FOUND"), ("artist_credit", "ARTIST_NOT_FOUND")):
        name = _words(identity[field])
        if name:
            # Word boundaries prevent U matching every occurrence of 'music'.
            if (
                name not in body
                if not any(c.isascii() and c.isalnum() for c in name)
                else " " + name + " " not in body
            ):
                return reason
        elif not any(line == identity[field] for line in lines):
            return reason
    return None
