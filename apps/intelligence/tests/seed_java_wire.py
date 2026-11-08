"""Actual Python control client and seed consumer against a Java/PostgreSQL test provider."""

import asyncio
import json
import sys
from datetime import datetime, timezone
from hashlib import sha256
from types import SimpleNamespace
from uuid import uuid4

from auralis_intelligence.agents.http import ControlHTTPClient
from auralis_intelligence.album_reviews.seed_context import SeedContext
from auralis_intelligence.contracts import digest
from auralis_intelligence.providers.excerpts import excerpt


async def run():
    base, execution, seed = sys.argv[1:]
    client = ControlHTTPClient(base, "be009-loopback")
    try:
        state = await client.request(execution, "seed-evidence", {})
        identity = state["items"][0]["identity"]
        assert state["items"][0]["evidence"] is None
        read = uuid4().hex
        url = "https://example.com/java-loopback"
        granted = await client.request(
            execution,
            "read-authorization",
            {"read_id": read, "candidate_id": seed, "url": url, "purpose": "SCREEN_RESEARCH"},
        )
        assert granted["decision"] == "GRANTED"
        text = excerpt("艺人 专辑 piano guitar rhythm", 3500)
        at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        receipt = {
            "read_id": read,
            "candidate_id": seed,
            "purpose": "SCREEN_RESEARCH",
            "requested_url": url,
            "final_url": url,
            "publisher_key": "example.com",
            "status": "READ_OK",
            "failure_reason": None,
            "read_at": at,
            "content_digest": "sha256:" + sha256(text.encode("utf-8")).hexdigest(),
            "text_chars": len(text),
            "near_duplicate_of": None,
            "checks": {"mentions_title": True, "mentions_artist": True},
            "retrieval_mode": "NETWORK",
            "excerpt_digest": digest(text),
        }
        assert (await client.request(execution, "read-receipts", {"items": [receipt]}))[
            "accepted_read_ids"
        ] == [read]
        record = {
            "seed_id": seed,
            "identity": identity,
            "status": "READ_AVAILABLE",
            "sources": [
                {
                    "read_id": read,
                    "url": url,
                    "read_at": at,
                    "text": text,
                    "text_digest": digest(text),
                }
            ],
        }
        state = await client.request(execution, "seed-evidence-save", {"items": [record]})
        seeds = SeedContext(None, None)
        seeds.accept_state(state)
        assert seeds.rows[0] == record

        async def request(operation, payload):
            return await client.request(execution, operation, payload)

        await seeds.collect(SimpleNamespace(control_request=request))
        assert seeds.reused_count == 1 and seeds.fetched_count == 0
        print(
            json.dumps(
                {"registered": 1, "reused": seeds.reused_count, "fetched": seeds.fetched_count}
            )
        )
    finally:
        await client.close()


if __name__ == "__main__":
    asyncio.run(run())
