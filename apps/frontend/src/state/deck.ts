import type { ProductCard, ProductState } from "../api/types.ts";

/** One album in the order the person meets it. */
export interface DeckEntry {
  candidateId: string;
  slotId: string;
  card: ProductCard;
  /**
   * Marked 已听过 here. The server then gives the slot to a new album; the marked one stays
   * in the deck (dimmed) so the person is not yanked off the record they were reading, and
   * the new one joins at the end.
   */
  heard: boolean;
}

/** READY cards in slot order. */
export function readyCards(state: ProductState | null) {
  if (!state) return [];
  return state.slots.flatMap((slot) =>
    slot.status === "READY" && slot.card
      ? [{ slotId: slot.slot_id, card: slot.card }]
      : [],
  );
}

export const freshDeck = (state: ProductState | null): DeckEntry[] =>
  readyCards(state).map(({ slotId, card }) => ({
    candidateId: card.candidate_id,
    slotId,
    card,
    heard: false,
  }));

/**
 * Follows the server state without reshuffling what the person already saw: entries keep
 * their position, cards still in a slot are refreshed (long-review state changes), marked
 * entries survive leaving their slot, and new cards are appended.
 */
export function reconcileDeck(
  entries: DeckEntry[],
  state: ProductState | null,
): DeckEntry[] {
  const present = new Map(
    readyCards(state).map((item) => [item.card.candidate_id, item]),
  );
  const kept: DeckEntry[] = [];
  for (const entry of entries) {
    const live = present.get(entry.candidateId);
    if (live) kept.push({ ...entry, slotId: live.slotId, card: live.card });
    else if (entry.heard) kept.push(entry);
  }
  const known = new Set(kept.map((entry) => entry.candidateId));
  for (const [candidateId, item] of present)
    if (!known.has(candidateId))
      kept.push({
        candidateId,
        slotId: item.slotId,
        card: item.card,
        heard: false,
      });
  return kept;
}

export const sameDeck = (a: DeckEntry[], b: DeckEntry[]) =>
  a.length === b.length &&
  a.every(
    (entry, index) =>
      entry.candidateId === b[index]?.candidateId &&
      entry.heard === b[index]?.heard &&
      entry.card === b[index]?.card,
  );
