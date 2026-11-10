import { afterEach, expect, test, vi } from "vitest";
import { acquireCover, coverKey, coverUrl } from "../../src/state/covers.ts";

afterEach(() => vi.unstubAllGlobals());

test("only canonical same-origin image routes receive cookies", () => {
  vi.stubGlobal("window", { location: { origin: "http://127.0.0.1:4173" } });
  const url = coverUrl("00000000-0000-0000-0000-000000000001");
  expect(coverKey(url)).toBe(url);
  expect(coverKey("https://example.com" + url)).toBeNull();
  expect(coverKey("/v1/bootstrap")).toBeNull();
  expect(coverKey("javascript:alert(1)")).toBeNull();
  expect(coverKey(url.replace("500", "9999"))).toBeNull();
});

test("two consumers share one fetch and receive only decoded images", async () => {
  const fetch = vi.fn(
    async () => new Response(new Blob(["image"], { type: "image/png" })),
  );
  vi.stubGlobal("fetch", fetch);
  let decoded = false;
  vi.stubGlobal(
    "Image",
    class {
      naturalWidth = 10;
      naturalHeight = 10;
      async decode() {
        decoded = true;
      }
    },
  );
  const a = acquireCover(coverUrl("00000000-0000-0000-0000-000000000002"));
  const b = acquireCover(coverUrl("00000000-0000-0000-0000-000000000002"));
  expect(a.promise).toBe(b.promise);
  const result = await a.promise;
  expect(decoded).toBe(true);
  expect(result?.url.startsWith("blob:")).toBe(true);
  expect(fetch).toHaveBeenCalledTimes(1);
  a.release();
  b.release();
});

test("no cover keeps a placeholder and is not fetched again on each render", async () => {
  const fetch = vi.fn(async () => new Response(null, { status: 404 }));
  vi.stubGlobal("fetch", fetch);
  const key = coverUrl("00000000-0000-0000-0000-000000000003");
  const a = acquireCover(key);
  expect(await a.promise).toBeNull();
  a.release();
  const b = acquireCover(key);
  expect(await b.promise).toBeNull();
  b.release();
  expect(fetch).toHaveBeenCalledTimes(1);
});
