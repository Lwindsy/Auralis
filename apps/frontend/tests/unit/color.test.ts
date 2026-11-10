import { describe, expect, test } from "vitest";
import {
  apcaContrast,
  hexToOklab,
  placeholderSwatches,
  roomFor,
} from "../../src/lib/color.ts";
import { coverArt } from "../../src/lib/cover.ts";
import { genreName } from "../../src/lib/text.ts";
import { listenersToReach, reachToListeners } from "../../src/state/draft.ts";

describe("room colour", () => {
  const seeds = Array.from({ length: 400 }, (_, i) => `album ${i}\u0000artist`);

  test("text on every room reads well (APCA |Lc| ≥ 75)", () => {
    for (const seed of seeds) {
      const art = coverArt(seed);
      const { room, ink } = roomFor(
        placeholderSwatches(art.base, art.accent, art.markShare),
      );
      expect(Math.abs(apcaContrast(ink, room))).toBeGreaterThanOrEqual(75);
    }
  });

  test("the room stays apart from the sleeve's main colour", () => {
    for (const seed of seeds) {
      const art = coverArt(seed);
      const { room } = roomFor(
        placeholderSwatches(art.base, art.accent, art.markShare),
      );
      const big = hexToOklab(art.markShare > 0.5 ? art.accent : art.base);
      expect(Math.abs(hexToOklab(room).L - big.L)).toBeGreaterThanOrEqual(
        0.065,
      );
    }
  });

  test("the room keeps the hue of a vivid accent on a grey field", () => {
    const { room } = roomFor(placeholderSwatches("#141414", "#d94f30", 0.25));
    const { a, b } = hexToOklab(room);
    expect(Math.hypot(a, b)).toBeGreaterThan(0.03);
    expect(a).toBeGreaterThan(0);
  });
});

describe("names and scales", () => {
  test("genre names are title-cased consistently", () => {
    expect(genreName("space rock revival")).toBe("Space Rock Revival");
    expect(genreName("uk garage")).toBe("UK Garage");
    expect(genreName("j-pop")).toBe("J-Pop");
    expect(genreName("r&b")).toBe("R&B");
    expect(genreName("Madchester")).toBe("Madchester");
  });

  test("传播度 maps linearly onto 0–500 listeners", () => {
    expect(reachToListeners(0)).toBe(1);
    expect(reachToListeners(10)).toBe(50);
    expect(reachToListeners(40)).toBe(200);
    expect(reachToListeners(100)).toBe(500);
    expect(listenersToReach(50)).toBe(10);
    expect(listenersToReach(5000)).toBe(100);
  });
});
