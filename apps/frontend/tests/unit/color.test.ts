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

  test("the spark is the sleeve's own hue and reads on its room (APCA |Lc| ≥ 60)", () => {
    for (const seed of seeds) {
      const art = coverArt(seed);
      const { room, spark } = roomFor(
        placeholderSwatches(art.base, art.accent, art.markShare),
      );
      expect(Math.abs(apcaContrast(spark, room))).toBeGreaterThanOrEqual(60);
    }
    const warm = roomFor(placeholderSwatches("#141414", "#d94f30", 0.25));
    const { a, b } = hexToOklab(warm.spark);
    expect(Math.hypot(a, b)).toBeGreaterThan(0.08);
    expect(a).toBeGreaterThan(0);
    const grey = roomFor(placeholderSwatches("#3a3a3a", "#9a9a9a", 0.3));
    expect(grey.spark).toBe(grey.ink);
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

  test("传播度 maps onto three inclusive listener bands", () => {
    expect(reachToListeners(0)).toEqual({ min: 0, max: 100 });
    expect(reachToListeners(1)).toEqual({ min: 3, max: 103 });
    expect(reachToListeners(40)).toEqual({ min: 100, max: 200 });
    expect(reachToListeners(41)).toEqual({ min: 223, max: 273 });
    expect(reachToListeners(70)).toEqual({ min: 310, max: 360 });
    expect(reachToListeners(71)).toEqual({ min: 442 });
    expect(reachToListeners(100)).toEqual({ min: 500 });
    for (let reach = 0; reach <= 100; reach++) {
      expect(listenersToReach(reachToListeners(reach).min)).toBe(reach);
    }
    expect(listenersToReach(5000)).toBe(100);
  });
});
