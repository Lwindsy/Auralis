import { describe, expect, test } from "vitest";
import { freshDeck, reconcileDeck, sameDeck } from "../../src/state/deck.ts";
import { awaitingContinuation, needsPolling } from "../../src/state/input.ts";
import { card, ready } from "../fixtures.ts";

describe("deck", () => {
  test("keeps order, refreshes live cards and appends newcomers", () => {
    const state = ready();
    const deck = freshDeck(state);
    expect(deck.map((entry) => entry.candidateId)).toEqual([
      "candidate-1",
      "candidate-2",
      "candidate-3",
    ]);
    const next = structuredClone(state);
    next.slots[1]!.card!.long_review_state = "READY";
    const refreshed = reconcileDeck(deck, next);
    expect(refreshed[1]!.card.long_review_state).toBe("READY");
    expect(sameDeck(deck, refreshed)).toBe(false);
    expect(sameDeck(refreshed, reconcileDeck(refreshed, next))).toBe(true);
  });

  test("a heard album stays in place while its slot is supplied; others leave", () => {
    const state = ready();
    const deck = freshDeck(state).map((entry) =>
      entry.candidateId === "candidate-1" ? { ...entry, heard: true } : entry,
    );
    const next = structuredClone(state);
    next.slots[0]!.card = card(10);
    next.slots[2]!.card = card(11);
    const after = reconcileDeck(deck, next);
    expect(after.map((entry) => [entry.candidateId, entry.heard])).toEqual([
      ["candidate-1", true],
      ["candidate-2", false],
      ["candidate-10", false],
      ["candidate-11", false],
    ]);
  });

  test("an exhausted round with empty slots is watched, not polled forever", () => {
    const state = ready();
    state.slots.forEach((slot) => {
      slot.card!.long_review_state = "READY";
    });
    state.supply_state = "R_EXHAUSTED";
    state.slots[2] = {
      slot_id: "slot-3",
      status: "EMPTY",
      card: null,
      message_key: null,
    };
    expect(awaitingContinuation(state)).toBe(true);
    expect(needsPolling(state)).toBe(false);
    state.supply_state = "FINAL_EXHAUSTED";
    expect(awaitingContinuation(state)).toBe(false);
  });
});
