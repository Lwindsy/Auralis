"""Complete MusicBrainz directory titles remain lossless across the shared wire boundary."""

import pytest
from auralis_intelligence.contracts import ContractError, validate_definition


def test_full_musicbrainz_title_limit_counts_unicode_codepoints():
    identity = {
        "title": "音" * 1024,
        "artist_credit": "Artist",
        "first_release_year": None,
        "release_type": None,
        "primary_genres": [],
    }
    validate_definition("Identity", identity)
    identity["title"] = "🎵" * 1024
    validate_definition("Identity", identity)
    identity["title"] += "x"
    with pytest.raises(ContractError):
        validate_definition("Identity", identity)
