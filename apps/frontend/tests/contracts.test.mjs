import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import fixtures from "../src/generated/fixtures.json" with { type: "json" };
import provenance from "../src/generated/provenance.json" with { type: "json" };
import { Api, validate } from "../src/api.js";
import { ready, review, shared } from "./fixtures.mjs";

test("shared product fixtures match structural contract including rejection cases", () => {
  for (const row of fixtures) {
    if (row.structural_valid)
      assert.doesNotThrow(
        () => validate(row.definition, row.payload),
        row.name,
      );
    else assert.throws(() => validate(row.definition, row.payload), row.name);
  }
  for (const count of [0, 1, 3, 5]) validate("ProductState", ready(count, 5));
  for (const status of [
    "GENERATING",
    "READY",
    "FAILED",
    "INTERRUPTED",
    "TIMED_OUT",
  ])
    validate("LongReviewView", review("candidate", status));
});
test("generated validation is bound to the actual shared schema", async () => {
  const text = await readFile(
    new URL(
      "../../intelligence/src/auralis_intelligence/contracts/agent-v1.schema.json",
      import.meta.url,
    ),
  );
  assert.equal(
    createHash("sha256").update(text).digest("hex"),
    provenance.schema_sha256,
  );
});
test("API uses same-origin credentials and CSRF; validates writes before sending", async () => {
  const calls = [];
  const api = new Api(async (url, options) => {
    calls.push({ url, options });
    return new Response(
      JSON.stringify(
        url === "/v1/bootstrap"
          ? shared("bootstrap-restores-waiting")
          : ready(),
      ),
      { status: 200 },
    );
  });
  await api.request("POST", "/v1/bootstrap", { body: {} });
  await api.request(
    "POST",
    "/v1/discovery-sessions/{session_id}/cards/{slot_id}/actions",
    {
      params: { session_id: "a/b", slot_id: "1" },
      body: { action: "REPLACE", expected_revision: 1 },
      key: "same-key",
    },
  );
  assert.equal(calls[1].url, "/v1/discovery-sessions/a%2Fb/cards/1/actions");
  assert.equal(calls[1].options.headers["X-CSRF-Token"], "t".repeat(32));
  assert.equal(calls[1].options.credentials, "same-origin");
  assert.equal(calls[1].options.cache, "no-store");
  await assert.rejects(
    api.request("POST", "/v1/discovery-sessions", {
      body: { subject_id: "forged" },
      key: "x",
    }),
  );
  assert.equal(calls.length, 2);
});
test("malformed and unexpected server responses fail closed", async () => {
  const api = new Api(
    async () => new Response(JSON.stringify({ ...ready(), revision: "1" })),
  );
  await assert.rejects(
    api.request("GET", "/v1/current-state"),
    /INVALID_RESPONSE/,
  );
});

test("1024-codepoint catalog titles survive consumer validation without truncation", () => {
  const result = shared("search-deduplicated-mbid");
  result.items[0].identity.title = "🎵".repeat(1024);
  validate("AlbumSearchResult", result);
  assert.equal([...result.items[0].identity.title].length, 1024);
  result.items[0].identity.title += "x";
  assert.throws(() => validate("AlbumSearchResult", result));
});
