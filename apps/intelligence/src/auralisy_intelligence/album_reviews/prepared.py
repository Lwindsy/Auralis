"""Public source excerpts reused with provenance, age checks and current read grants."""

import json
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path

from auralisy_intelligence.providers.web import FetchFailure, FetchResponse


class PreparedEvidence:
    def __init__(self, path: Path, *, max_age_days: int = 7):
        document = json.loads(Path(path).read_text(encoding="utf-8"))
        if document.get("format") != "prepared-evidence.v1":
            raise ValueError("PREPARED_EVIDENCE_VERSION")
        self.albums = document["albums"]
        self.max_age_seconds = max_age_days * 86400
        self.pages = {}
        for album in self.albums.values():
            for source in album["sources"]:
                if sha256(source["text"].encode()).hexdigest() != source["sha256"]:
                    raise ValueError("PREPARED_EVIDENCE_DIGEST")
                self.pages[source["url"]] = source

    def sources(self, candidate_id: str):
        now = datetime.now(timezone.utc)
        return [
            source
            for source in self.albums.get(candidate_id, {}).get("sources", [])
            if 0
            <= (
                now - datetime.fromisoformat(source["read_at"].replace("Z", "+00:00"))
            ).total_seconds()
            <= self.max_age_seconds
        ]

    async def fetch(self, url, max_bytes, timeout_seconds):
        source = self.pages.get(url)
        if source is None:
            raise FetchFailure("EVIDENCE_NOT_PREPARED")
        age = (
            datetime.now(timezone.utc)
            - datetime.fromisoformat(source["read_at"].replace("Z", "+00:00"))
        ).total_seconds()
        if not 0 <= age <= self.max_age_seconds:
            raise FetchFailure("EVIDENCE_STALE")
        body = source["text"].encode()
        if len(body) > max_bytes:
            raise FetchFailure("BODY_TOO_LARGE")
        return FetchResponse(
            200,
            {"content-type": "text/plain"},
            body,
            retrieval_mode="PREPARED_EXCERPT",
            observed_at=source["read_at"],
        )
