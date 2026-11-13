import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { describe, expect, test } from "vitest";
import fixtures from "../../src/generated/fixtures.json" with { type: "json" };
import provenance from "../../src/generated/provenance.json" with { type: "json" };
import { Api, validate, type DefinitionName } from "../../src/api/client.ts";
import type { AlbumSearchResult, BootstrapState } from "../../src/api/types.ts";
import { ready, review, shared } from "../fixtures.ts";

describe("contract consumer", () => {
  test("shared product fixtures match structural contract including rejection cases", () => {
    for (const row of fixtures as {
      name: string;
      definition: DefinitionName;
      structural_valid: boolean;
      payload: unknown;
    }[]) {
      if (row.structural_valid)
        expect(
          () => validate(row.definition, row.payload),
          row.name,
        ).not.toThrow();
      else
        expect(() => validate(row.definition, row.payload), row.name).toThrow();
    }
    for (const count of [0, 1, 3, 5]) validate("ProductState", ready(count, 5));
    for (const status of [
      "GENERATING",
      "READY",
      "FAILED",
      "INTERRUPTED",
      "TIMED_OUT",
    ] as const)
      validate("LongReviewView", review("candidate", status));
  });

  test("generated validation is bound to the actual shared schema", async () => {
    const text = await readFile(
      new URL(
        "../../../intelligence/src/auralisy_intelligence/contracts/agent-v1.schema.json",
        import.meta.url,
      ),
    );
    expect(createHash("sha256").update(text).digest("hex")).toBe(
      provenance.schema_sha256,
    );
  });

  test("API uses same-origin credentials and CSRF; validates writes before sending", async () => {
    const calls: { url: string; options: RequestInit }[] = [];
    const api = new Api(async (url, options) => {
      calls.push({ url, options });
      return new Response(
        JSON.stringify(
          url === "/v1/bootstrap"
            ? shared<BootstrapState>("bootstrap-restores-waiting")
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
    expect(calls[1]!.url).toBe("/v1/discovery-sessions/a%2Fb/cards/1/actions");
    const headers = calls[1]!.options.headers as Record<string, string>;
    expect(headers["X-CSRF-Token"]).toBe("t".repeat(32));
    expect(headers["Idempotency-Key"]).toBe("same-key");
    expect(calls[1]!.options.credentials).toBe("same-origin");
    expect(calls[1]!.options.cache).toBe("no-store");
    await expect(
      api.request("POST", "/v1/discovery-sessions", {
        body: { subject_id: "forged" },
        key: "x",
      }),
    ).rejects.toThrow();
    expect(calls).toHaveLength(2);
  });

  test("writes are refused before bootstrap provides a CSRF token", async () => {
    const api = new Api(async () => new Response("{}"));
    await expect(
      api.request("POST", "/v1/discovery-sessions/{session_id}/next-batch", {
        params: { session_id: "s" },
        body: { expected_revision: 1 },
        key: "k",
      }),
    ).rejects.toThrow("SESSION_NOT_READY");
  });

  test("malformed and unexpected server responses fail closed", async () => {
    const api = new Api(
      async () => new Response(JSON.stringify({ ...ready(), revision: "1" })),
    );
    await expect(api.request("GET", "/v1/current-state")).rejects.toThrow(
      "INVALID_RESPONSE",
    );
    const teapot = new Api(
      async () => new Response(JSON.stringify(ready()), { status: 418 }),
    );
    await expect(teapot.request("GET", "/v1/current-state")).rejects.toThrow(
      "UNEXPECTED_STATUS",
    );
  });

  test("1024-codepoint catalog titles survive consumer validation without truncation", () => {
    const result = shared<AlbumSearchResult>("search-deduplicated-mbid");
    result.items[0]!.identity.title = "🎵".repeat(1024);
    validate("AlbumSearchResult", result);
    expect([...result.items[0]!.identity.title]).toHaveLength(1024);
    result.items[0]!.identity.title += "x";
    expect(() => validate("AlbumSearchResult", result)).toThrow();
  });
});
