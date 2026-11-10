import { flushSync } from "react-dom";

type ViewTransitionDocument = Document & {
  startViewTransition?: (update: () => void) => { finished: Promise<void> };
};

/**
 * Runs a page-level state change inside a View Transition so whole scenes cross over
 * instead of cutting. Without the API, or with reduced motion, the change just happens.
 */
export function withViewTransition(update: () => void) {
  const doc = document as ViewTransitionDocument;
  if (
    !doc.startViewTransition ||
    window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
  ) {
    update();
    return;
  }
  doc.startViewTransition(() => flushSync(update));
}

/** The page chrome follows the colour of the room (the album on the platter). */
export function paintPage(room: string, ink: string) {
  const root = document.documentElement.style;
  root.setProperty("--page-room", room);
  root.setProperty("--page-ink", ink);
  let tag = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (!tag) {
    tag = document.createElement("meta");
    tag.name = "theme-color";
    document.head.appendChild(tag);
  }
  tag.content = room;
}
