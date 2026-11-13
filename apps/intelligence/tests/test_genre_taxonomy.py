import json

import pytest
from auralisy_intelligence.genre_taxonomy import check_artifacts, load_taxonomy, taxonomy_hash


def test_rejects_missing_parents_cycles_and_mixed_artifacts(tmp_path):
    taxonomy = {
        "format_version": 1,
        "source_sha256": "a" * 64,
        "core_manifest_sha256": "b" * 64,
        "genres": [
            {"id": "root", "name": "Root", "aliases": [], "parents": []},
            {"id": "child", "name": "Child", "aliases": [], "parents": ["root"]},
        ],
    }
    path = tmp_path / "taxonomy.json"
    path.write_text(json.dumps(taxonomy))
    assert len(load_taxonomy(path)["genres"]) == 2
    revision = taxonomy_hash(path)
    reference = tmp_path / "reference.json"
    reference.write_text(json.dumps({"taxonomy_sha256": revision, "genres": taxonomy["genres"]}))
    snapshot = tmp_path / "snapshot.json"
    value = {
        "tag_ids": ["root", "child"],
        "tag_parents": [["root", "child"]],
        "provenance": {"taxonomy_sha256": revision},
    }
    snapshot.write_text(json.dumps(value))
    check_artifacts(path, reference, snapshot)
    value["tag_parents"] = []
    snapshot.write_text(json.dumps(value))
    with pytest.raises(ValueError, match="GENRE_ARTIFACT_MISMATCH"):
        check_artifacts(path, reference, snapshot)
    taxonomy["genres"][0]["parents"] = ["child"]
    path.write_text(json.dumps(taxonomy))
    with pytest.raises(ValueError, match="TAXONOMY_CYCLE"):
        load_taxonomy(path)
    taxonomy["genres"][0]["parents"] = ["missing"]
    path.write_text(json.dumps(taxonomy))
    with pytest.raises(ValueError, match="TAXONOMY_PARENT_MISSING"):
        load_taxonomy(path)
