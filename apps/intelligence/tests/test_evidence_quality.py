"""Technical false positives and one-call semantic admission boundaries."""

from copy import deepcopy
from types import SimpleNamespace

import pytest
from auralis_intelligence.album_reviews.batch import BatchResearchStage
from auralis_intelligence.album_reviews.evidence_quality import page_rejection
from auralis_intelligence.contracts import ContractError


@pytest.mark.parametrize(
    "text,identity,reason",
    [
        ("", {"title": "U", "artist_credit": "underscores"}, "EMPTY_PAGE"),
        (
            "Sign in to continue\n" + "underscores U " * 40,
            {"title": "U", "artist_credit": "underscores"},
            "ACCESS_PAGE",
        ),
        ("underscores U", {"title": "U", "artist_credit": "underscores"}, "TEXT_TOO_SHORT"),
        (
            "underscores makes music " * 40,
            {"title": "U", "artist_credit": "underscores"},
            "TITLE_NOT_FOUND",
        ),
        (
            "U by another band " * 40,
            {"title": "U", "artist_credit": "underscores"},
            "ARTIST_NOT_FOUND",
        ),
        (
            "underscores — U\n"
            + "The songs use layered vocals and drums. " * 20
            + "Sign in to continue",
            {"title": "U", "artist_credit": "underscores"},
            None,
        ),
        (
            "Beyonce — Renaissance\n" + "Dense rhythms support the melodies. " * 20,
            {"title": "Renaissance", "artist_credit": "Beyoncé"},
            None,
        ),
        (
            "周杰伦的《范特西》\n" + "这段合成资料描述旋律与人声的变化。" * 30,
            {"title": "范特西", "artist_credit": "周杰伦"},
            None,
        ),
        (
            "Kesha\n.\n" + "The album blends bright vocals and drums. " * 20,
            {"title": ".", "artist_credit": "Kesha"},
            None,
        ),
    ],
)
def test_technical_filter_does_not_claim_semantic_sufficiency(text, identity, reason):
    assert page_rejection(text, identity) == reason


@pytest.mark.parametrize("case", ["missing", "duplicate", "foreign_ref", "insufficient_card"])
def test_batch_rejects_unverifiable_assessments(case):
    dossier = SimpleNamespace(reads=[SimpleNamespace(ref="R1")])
    aliases = {"C1": ({}, dossier), "C2": ({}, dossier)}
    output = {
        "assessments": [{"c": c, "reason": "SUFFICIENT", "refs": ["R1"]} for c in aliases],
        "cards": [],
    }
    output = deepcopy(output)
    if case == "missing":
        output["assessments"].pop()
    elif case == "duplicate":
        output["assessments"][1]["c"] = "C1"
    elif case == "foreign_ref":
        output["assessments"][0]["refs"] = ["R9"]
    else:
        output["assessments"][0]["reason"] = "NO_MUSIC_DESCRIPTION"
        output["cards"] = [{"c": "C1", "score": 4, "refs": ["R1"], "text": "invalid"}]
    with pytest.raises(ContractError):
        BatchResearchStage._validate(output, aliases, 3, {})
