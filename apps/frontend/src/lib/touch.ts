/**
 * Phones and tablets: a touch screen with no hover. Desktop mice and trackpads never
 * match, so everything gated on this leaves the desktop page exactly as it is.
 */
export const isTouchDevice = (): boolean =>
  typeof window !== "undefined" &&
  !!window.matchMedia?.("(hover: none) and (pointer: coarse)").matches;

/**
 * What the page keeps in its own history entry (not in browser storage): the album on
 * screen, so a reload — a pull-to-refresh that slipped through, a tab the phone dropped
 * in the background — comes back to the same album instead of the first one.
 */
interface PageState {
  auralisAlbum?: string;
  auralisLiner?: boolean;
}

export const pageState = (): PageState =>
  (typeof history !== "undefined" && (history.state as PageState | null)) || {};

export const writePageState = (patch: PageState) =>
  history.replaceState({ ...pageState(), ...patch }, "");
