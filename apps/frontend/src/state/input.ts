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
