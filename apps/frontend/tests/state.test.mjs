import test from "node:test";
import assert from "node:assert/strict";
import { Api, ApiError } from "../src/api.js";
import {
  DiscoveryController,
  emptyInput,
  hasInput,
  needsPolling,
} from "../src/state.js";
import { FixtureBackend, ready, idle } from "./fixtures.mjs";

function setup(state = ready()) {
  const backend = new FixtureBackend(state);
  const api = new Api(async (url, options) => {
    const result = backend.handle(
      options.method,
      url,
      options.body ? JSON.parse(options.body) : undefined,
      Object.fromEntries(
        Object.entries(options.headers).map(([k, v]) => [k.toLowerCase(), v]),
      ),
    );
    return new Response(JSON.stringify(result.body), { status: result.status });
  });
  return { backend, controller: new DiscoveryController(api) };
}
test("only mode or whitespace is not input; negative-only is valid", () => {
  assert.equal(hasInput(emptyInput()), false);
  assert.equal(hasInput({ ...emptyInput(), request_text: " \n\t" }), false);
  assert.equal(
    hasInput({ ...emptyInput(), disliked_genre_ids: ["metal"] }),
    true,
  );
  assert.equal(hasInput({ ...emptyInput(), request_text: "爵士🎵" }), true);
});
test("HEARD records identity; REPLACE does not; other slots survive automatic supply", async (t) => {
  const { controller, backend } = setup();
  t.after(() => controller.dispose());
  await controller.connect();
  const expiry = controller.state.expires_at;
  await controller.action("card", "slot-1", "REPLACE");
  assert.equal(controller.state.slots[0].card, null);
  assert.equal(backend.heard.size, 0);
  assert.equal(controller.state.slots[1].card.candidate_id, "candidate-2");
  await controller.refresh();
  assert.ok(controller.state.slots[0].card);
  await controller.action("card", "slot-2", "HEARD");
  assert.ok(backend.heard.has("candidate-2"));
  await controller.refresh();
  assert.equal(controller.state.expires_at, expiry);
});
test("lost write response retry retains exact body and key, blocks a second intent", async (t) => {
  const calls = [];
  let fail = true;
  const api = {
    csrf: "token",
    request: async (method, path, options) => {
      calls.push(structuredClone(options));
      if (fail) throw new ApiError("NETWORK_UNCERTAIN");
      return ready();
    },
  };
  const controller = new DiscoveryController(api);
  t.after(() => controller.dispose());
  controller.state = ready();
  await controller.action("card", "slot-1", "HEARD");
  await controller.action("next-batch");
  assert.equal(calls.length, 1);
  assert.ok(controller.hiddenSlots.has("slot-1"));
  fail = false;
  await controller.retry();
  assert.deepEqual(calls[0], calls[1]);
  assert.equal(controller.pending, null);
});
test("stale poll cannot overwrite a later mutation", async (t) => {
  let resolvePoll;
  const api = {
    request: (method) =>
      method === "GET"
        ? new Promise((resolve) => {
            resolvePoll = resolve;
          })
        : Promise.resolve({ ...ready(), revision: 3 }),
  };
  const controller = new DiscoveryController(api);
  t.after(() => controller.dispose());
  controller.state = ready();
  const poll = controller.refresh();
  await controller.action("next-batch");
  resolvePoll({ ...ready(), revision: 2 });
  await poll;
  assert.equal(controller.state.revision, 3);
});
test("queue-full rejection restores hidden slot and allows only same-key retry", async (t) => {
  const api = {
    request: async () => {
      throw new ApiError("QUEUE_FULL", {
        retry_disposition: "RETRY_SAME_KEY",
        idempotency_consumed: false,
      });
    },
  };
  const controller = new DiscoveryController(api);
  t.after(() => controller.dispose());
  controller.state = ready();
  await controller.action("card", "slot-1", "REPLACE");
  assert.equal(controller.hiddenSlots.size, 0);
  assert.ok(controller.pending);
});
test("read-only refresh and restore never send a user action or extend expiry", async (t) => {
  const { controller, backend } = setup();
  t.after(() => controller.dispose());
  await controller.connect();
  const expiry = controller.state.expires_at;
  await controller.refresh();
  await controller.refresh();
  assert.equal(controller.state.expires_at, expiry);
  assert.deepEqual(
    backend.calls.map((call) => call.method),
    ["POST", "GET", "GET"],
  );
  assert.equal(backend.calls[0].path, "/v1/bootstrap");
});
test("expiry clears recoverable UI state and prevents writes", async (t) => {
  const { controller } = setup();
  t.after(() => controller.dispose());
  await controller.connect();
  controller.state.expires_at = "2000-01-01T00:00:00Z";
  controller.schedule();
  assert.equal(controller.state, null);
  assert.equal(controller.api.csrf, null);
});
test("background and waiting poll, paused supply and failed reviews do not", () => {
  const state = ready();
  state.slots.forEach((slot) => {
    slot.card.long_review_state = "FAILED";
  });
  assert.equal(needsPolling(state), false);
  state.supply_state = "PAUSED";
  assert.equal(needsPolling(state), false);
  state.status = "WAITING";
  assert.equal(needsPolling(state), true);
});

test("local expiry automatically bootstraps a fresh empty identity", async (t) => {
  const { controller, backend } = setup();
  t.after(() => controller.dispose());
  await controller.connect();
  backend.state = { ...idle(), message_key: "session.expired" };
  controller.expire();
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(controller.state.session_id, null);
  assert.equal(controller.state.message_key, "session.expired");
  assert.equal(controller.pending, null);
  assert.equal(backend.calls.at(-1).path, "/v1/bootstrap");
});
