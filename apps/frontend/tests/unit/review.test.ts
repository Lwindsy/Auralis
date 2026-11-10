import { describe, expect, test } from "vitest";
import { ApiError } from "../../src/api/client.ts";
import type { ProductState } from "../../src/api/types.ts";
import type { ApiLike } from "../../src/state/controller.ts";
import { LongReviewController } from "../../src/state/review.ts";
import { card, ready, review } from "../fixtures.ts";

describe("long review controller", () => {
  test("ready review waits for the new interaction revision before the next open", async () => {
    let release: () => void = () => {};
    let revision = 1;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const requests: number[] = [];
    const api: ApiLike = {
      csrf: "t",
      request: async (_method, _path, options) => {
        requests.push(
          (options?.body as { expected_revision: number }).expected_revision,
        );
        return review("candidate-1", "READY") as never;
      },
    };
    const controller = new LongReviewController(
      api,
      () => ({ ...ready(), revision }) as ProductState,
      async () => {
        await gate;
        revision += 1;
      },
    );
    const opened = controller.open(card(1));
    await Promise.resolve();
    expect(controller.view?.review).toBeNull();
    release();
    await opened;
    expect(controller.view?.status).toBe("READY");
    expect(controller.view?.review).not.toBeNull();
    await controller.write("open");
    expect(requests).toEqual([1, 2]);
    controller.dispose();
  });

  test("failure keeps the original key for retry and never regenerates by itself", async () => {
    const keys: string[] = [];
    const paths: string[] = [];
    let fail = true;
    const api: ApiLike = {
      csrf: "t",
      request: async (_method, path, options) => {
        paths.push(path);
        keys.push(options!.key!);
        if (fail) throw new ApiError("NETWORK_UNCERTAIN");
        return review("candidate-1", "INTERRUPTED") as never;
      },
    };
    const controller = new LongReviewController(
      api,
      () => ready(),
      async () => {},
    );
    await controller.open(card(1));
    expect(controller.error?.retry).toBe(true);
    fail = false;
    await controller.write("open");
    expect(keys[0]).toBe(keys[1]);
    expect(controller.view?.status).toBe("INTERRUPTED");
    expect(paths.every((path) => path.endsWith("/open"))).toBe(true);
    controller.dispose();
  });

  test("closes when its card leaves the current state", async () => {
    const api: ApiLike = {
      csrf: "t",
      request: async () => review("candidate-1", "READY") as never,
    };
    const controller = new LongReviewController(
      api,
      () => ready(),
      async () => {},
    );
    await controller.open(card(1));
    expect(controller.isOpen).toBe(true);
    const next = ready();
    next.slots[0]!.card = card(9);
    controller.reconcile(next, new Set(["candidate-1"]));
    expect(controller.isOpen).toBe(true);
    controller.reconcile(next);
    expect(controller.isOpen).toBe(false);
    controller.dispose();
  });
});
