import test from "node:test";
import assert from "node:assert/strict";
import { ReviewDialog } from "../src/review.js";

test("ready review waits for the new interaction revision before the next open", async () => {
  let release;
  let revision = 1;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const requests = [];
  const rendered = [];
  const dialog = Object.assign(Object.create(ReviewDialog.prototype), {
    epoch: 1,
    pending: null,
    busy: false,
    session: "session",
    card: { candidate_id: "album" },
    body: { querySelectorAll: () => [] },
    state: () => ({ revision }),
    api: {
      request: async (_method, _path, request) => {
        requests.push(request.body.expected_revision);
        return { status: "READY" };
      },
    },
    refresh: async () => {
      await gate;
      revision += 1;
    },
    render: () => rendered.push(revision),
    schedule: () => {},
  });
  const first = dialog.write("open");
  await Promise.resolve();
  assert.deepEqual(rendered, []);
  release();
  await first;
  assert.deepEqual(rendered, [2]);
  await dialog.write("open");
  assert.deepEqual(requests, [1, 2]);
});
