import { afterEach, describe, expect, test } from "vitest";
import { Api, ApiError } from "../../src/api/client.ts";
import type { ProductError, ProductState } from "../../src/api/types.ts";
import {
  DiscoveryController,
  type ApiLike,
} from "../../src/state/controller.ts";
import { emptyInput, hasInput, needsPolling } from "../../src/state/input.ts";
import { FixtureBackend, idle, ready } from "../fixtures.ts";

const controllers: DiscoveryController[] = [];
afterEach(() => {
  while (controllers.length) controllers.pop()!.dispose();
});

function track(controller: DiscoveryController) {
  controllers.push(controller);
  return controller;
}

function setup(state: ProductState = ready()) {
  const backend = new FixtureBackend(state);
  const api = new Api(async (url, options) => {
    const result = backend.handle(
      options.method ?? "GET",
      url,
      options.body ? JSON.parse(String(options.body)) : undefined,
      Object.fromEntries(
        Object.entries(options.headers as Record<string, string>).map(
          ([k, v]) => [k.toLowerCase(), v],
        ),
      ),
    );
    return new Response(JSON.stringify(result.body), { status: result.status });
  });
  return { backend, controller: track(new DiscoveryController(api)) };
}

const stub = (request: ApiLike["request"]): ApiLike => ({
  csrf: "token",
  request,
});
const error = (code: string, details: Partial<ProductError>) =>
  new ApiError(code, {
    code,
    request_id: "r",
    message_key: code,
    retryable: true,
    idempotency_consumed: true,
    current_revision: null,
    retry_disposition: "RETRY_SAME_KEY",
    ...details,
  });

describe("discovery controller", () => {
  test("only mode or whitespace is not input; negative-only is valid", () => {
    expect(hasInput(emptyInput())).toBe(false);
    expect(hasInput({ ...emptyInput(), request_text: " \n\t" })).toBe(false);
    expect(hasInput({ ...emptyInput(), mode: "surprise" })).toBe(false);
    expect(hasInput({ ...emptyInput(), disliked_genre_ids: ["metal"] })).toBe(
      true,
    );
    expect(hasInput({ ...emptyInput(), request_text: "爵士🎵" })).toBe(true);
  });

  test("HEARD records identity; REPLACE does not; other slots survive automatic supply", async () => {
    const { controller, backend } = setup();
    await controller.connect();
    const expiry = controller.state!.expires_at;
    await controller.action("card", "slot-1", "REPLACE");
    expect(controller.state!.slots[0]!.card).toBeNull();
    expect(backend.heard.size).toBe(0);
    expect(controller.state!.slots[1]!.card!.candidate_id).toBe("candidate-2");
    await controller.refresh();
    expect(controller.state!.slots[0]!.card).toBeTruthy();
    await controller.action("card", "slot-2", "HEARD");
    expect(backend.heard.has("candidate-2")).toBe(true);
    await controller.refresh();
    expect(controller.state!.expires_at).toBe(expiry);
  });

  test("lost write response retry retains exact body and key, blocks a second intent", async () => {
    const calls: unknown[] = [];
    let fail = true;
    const controller = track(
      new DiscoveryController(
        stub(async (_method, _path, options) => {
          calls.push(structuredClone(options));
          if (fail) throw new ApiError("NETWORK_UNCERTAIN");
          return ready() as never;
        }),
      ),
    );
    controller.state = ready();
    await controller.action("card", "slot-1", "HEARD");
    await controller.action("next-batch");
    expect(calls).toHaveLength(1);
    expect(controller.hiddenSlots.has("slot-1")).toBe(true);
    expect(controller.locked).toBe(true);
    fail = false;
    await controller.retry();
    expect(calls[0]).toEqual(calls[1]);
    expect(controller.pending).toBeNull();
    expect(controller.hiddenSlots.size).toBe(0);
  });

  test("stale poll cannot overwrite a later mutation", async () => {
    let resolvePoll: (value: ProductState) => void = () => {};
    const controller = track(
      new DiscoveryController(
        stub((method) =>
          method === "GET"
            ? (new Promise((resolve) => {
                resolvePoll = resolve as never;
              }) as never)
            : (Promise.resolve({ ...ready(), revision: 3 }) as never),
        ),
      ),
    );
    controller.state = ready();
    const poll = controller.refresh();
    await controller.action("next-batch");
    resolvePoll({ ...ready(), revision: 2 });
    await poll;
    expect(controller.state.revision).toBe(3);
  });

  test("queue-full rejection restores hidden slot and allows only same-key retry", async () => {
    const controller = track(
      new DiscoveryController(
        stub(async () => {
          throw error("QUEUE_FULL", { idempotency_consumed: false });
        }),
      ),
    );
    controller.state = ready();
    await controller.action("card", "slot-1", "REPLACE");
    expect(controller.hiddenSlots.size).toBe(0);
    expect(controller.pending).toBeTruthy();
  });

  test("non-retryable dispositions drop the pending write", async () => {
    const controller = track(
      new DiscoveryController(
        stub(async () => {
          throw error("REVISION_CONFLICT", { retry_disposition: "POLL_STATE" });
        }),
      ),
    );
    controller.state = ready();
    await controller.action("next-batch");
    expect(controller.pending).toBeNull();
    expect(controller.error?.message).toBe("REVISION_CONFLICT");
  });

  test("read-only refresh and restore never send a user action or extend expiry", async () => {
    const { controller, backend } = setup();
    await controller.connect();
    const expiry = controller.state!.expires_at;
    await controller.refresh();
    await controller.refresh();
    expect(controller.state!.expires_at).toBe(expiry);
    expect(backend.calls.map((call) => call.method)).toEqual([
      "POST",
      "GET",
      "GET",
    ]);
    expect(backend.calls[0]!.path).toBe("/v1/bootstrap");
  });

  test("expiry clears recoverable UI state and prevents writes", async () => {
    const { controller } = setup();
    await controller.connect();
    controller.state!.expires_at = "2000-01-01T00:00:00Z";
    controller.schedule();
    expect(controller.state).toBeNull();
    expect(controller.api.csrf).toBeNull();
    expect(await controller.action("next-batch")).toBe(false);
  });

  test("background and waiting poll, paused supply and failed reviews do not", () => {
    const state = ready();
    state.slots.forEach((slot) => {
      slot.card!.long_review_state = "FAILED";
    });
    expect(needsPolling(state)).toBe(false);
    state.supply_state = "PAUSED";
    expect(needsPolling(state)).toBe(false);
    state.status = "WAITING";
    expect(needsPolling(state)).toBe(true);
  });

  test("local expiry automatically bootstraps a fresh empty identity", async () => {
    const { controller, backend } = setup();
    await controller.connect();
    backend.state = { ...idle(), message_key: "session.expired" };
    controller.expire();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(controller.state!.session_id).toBeNull();
    expect(controller.state!.message_key).toBe("session.expired");
    expect(controller.pending).toBeNull();
    expect(backend.calls.at(-1)!.path).toBe("/v1/bootstrap");
  });

  test("subscribers observe every visible change", async () => {
    const { controller } = setup();
    const versions: number[] = [];
    controller.subscribe(() => versions.push(controller.getVersion()));
    await controller.connect();
    expect(versions.length).toBeGreaterThanOrEqual(2);
    expect(versions).toEqual([...versions].sort((a, b) => a - b));
  });
});
