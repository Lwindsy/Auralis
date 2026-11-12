import { beforeAll, describe, expect, test } from "vitest";
import { message, note, stateMessage } from "../../src/api/messages.ts";
import { i18n } from "../../src/i18n/index.ts";
import { coverArt, hash, initials } from "../../src/lib/cover.ts";
import { ready } from "../fixtures.ts";

// The page opens in English; these checks read the reviewed Chinese copy.
beforeAll(() => i18n.set("zh-CN", false));

describe("presentation helpers", () => {
  test("unknown message keys never echo server text", () => {
    expect(message("SOME <b>server</b> TEXT")).toBe(
      "这次操作未能完成，请刷新状态后重试。",
    );
    expect(message("QUEUE_FULL")).toBe("当前等待人数较多，请稍后重试。");
    expect(note("session.restored")).toBeNull();
    expect(note("unknown.key")).toBeNull();
  });

  test("BASIC and partial states keep their reviewed copy", () => {
    const state = ready(2, 3);
    expect(stateMessage(state)).toBe(
      "本次找到的合格专辑不足目标数量，先听听这些。",
    );
    state.analysis_mode = "BASIC";
    expect(stateMessage(state)).toBe("本次个性化未完成，先看看这些基础推荐。");
  });

  test("cover art is deterministic and spreads nearby titles", () => {
    expect(coverArt("Kind of Blue")).toEqual(coverArt("Kind of Blue"));
    const palettes = new Set(
      Array.from({ length: 12 }, (_, i) => coverArt(`Vol. ${i}`).base),
    );
    expect(palettes.size).toBeGreaterThan(5);
    expect(hash("a")).not.toBe(hash("b"));
    expect(initials("  the Köln Concert")).toBe("T");
    expect(initials("…")).toBe("♪");
  });
});
