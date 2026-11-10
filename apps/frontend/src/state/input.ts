import type { DiscoveryInput, ProductState } from "../api/types.ts";

export const MAX_ALBUMS = 10;
export const MAX_TEXT = 16000;

export const emptyInput = (): DiscoveryInput => ({
  liked_genres: [],
  disliked_genre_ids: [],
  liked_albums: [],
  mode: "explore",
  request_text: "",
  language: "zh-CN",
});

/** Mode alone is not an input; whitespace-only text is not either; dislike-only is valid. */
export function hasInput(input: DiscoveryInput): boolean {
  return !!(
    input.liked_genres.length ||
    input.disliked_genre_ids.length ||
    input.liked_albums.length ||
    input.request_text.trim()
  );
}

export const codepoints = (text: string) => [...text].length;

const ACTIVE_REVIEW = ["GENERATING", "NEEDS_MORE_RESEARCH"];

export const needsPolling = (state: ProductState | null): boolean =>
  !!state &&
  (["RUNNING", "WAITING"].includes(state.status) ||
    state.supply_state === "SUPPLYING" ||
    state.slots.some(
      (slot) =>
        slot.status === "SUPPLYING" ||
        ACTIVE_REVIEW.includes(slot.card?.long_review_state ?? ""),
    ));

/**
 * A round that ran out of candidates while slots are still empty. After a user-requested
 * supply the server may start the next round on its own, so the page keeps reading for a
 * short while instead of declaring the slot lost.
 */
export const awaitingContinuation = (state: ProductState | null): boolean =>
  !!state &&
  state.supply_state === "R_EXHAUSTED" &&
  state.slots.some((slot) => slot.status === "EMPTY");

/** Background work that will change the visible group without another click. */
export const supplying = (state: ProductState): boolean =>
  ["RUNNING", "WAITING"].includes(state.status) ||
  state.supply_state === "SUPPLYING" ||
  state.slots.some((slot) => slot.status === "SUPPLYING");
