"""Read Java-admitted full-catalog counts independently of training/test partitions."""

from pathlib import Path
from types import MappingProxyType

from auralisy_intelligence.contracts import require_binding, validate_definition

from .io import read_json


class FullAudience:
    def __init__(self, path: Path, expected_binding: dict):
        validate_definition("AudienceBinding", expected_binding)
        artifact = read_json(path)
        validate_definition("FullAudienceSnapshot", artifact)
        self.binding = {
            key: artifact[key] for key in ("dataset_id", "window", "sample_listeners")
        } | {"snapshot_digest": artifact["digest"]}
        require_binding(self.binding, expected_binding)
        self.counts = MappingProxyType(
            {row["release_group_id"]: row["listeners"] for row in artifact["counts"]}
        )
        self.binding = MappingProxyType(self.binding)

    def audience(self, album_ids: list[str]) -> dict:
        return {key: self.binding[key] for key in ("dataset_id", "window", "sample_listeners")} | {
            "counts": {identifier: self.counts.get(identifier) for identifier in album_ids}
        }

    def binding_copy(self) -> dict:
        return dict(self.binding)


def matches_policy(audience: dict | None, policy: dict) -> bool:
    binding = policy.get("audience_binding")
    return bool(
        audience
        and binding
        and audience.get("source") == "LISTENBRAINZ_SAMPLE"
        and all(
            audience.get(key) == binding[key]
            for key in ("dataset_id", "window", "sample_listeners")
        )
        and audience.get("listeners") is not None
        and 0 <= audience["listeners"] <= binding["sample_listeners"]
    )
