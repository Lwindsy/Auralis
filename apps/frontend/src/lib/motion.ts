import { flushSync } from "react-dom";

type ViewTransitionDocument = Document & {
  startViewTransition?: (update: () => void) => { finished: Promise<void> };
};

const reducedMotion = () =>
  !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/**
 * Runs a page-level state change inside a View Transition so whole scenes cross over
 * instead of cutting. `kind` names the change on the root element while it runs, so
 * styles can give it its own timing (a language switch fades longer and keeps the switch
 * itself live). Without the API, a named change gets a light fade-in instead; with
 * reduced motion, the change just happens.
 */
export function withViewTransition(update: () => void, kind?: string) {
  const doc = document as ViewTransitionDocument;
  const root = document.documentElement;
  if (reducedMotion()) {
    update();
    return;
  }
  if (!doc.startViewTransition) {
    update();
    if (kind) {
      root.removeAttribute("data-refade");
      void root.offsetWidth;
      root.setAttribute("data-refade", kind);
    }
    return;
  }
  if (kind) root.dataset.transition = kind;
  const transition = doc.startViewTransition(() => flushSync(update));
  if (kind)
    void transition.finished.finally(() => {
      if (root.dataset.transition === kind) delete root.dataset.transition;
    });
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
