import { flushSync } from "react-dom";

type ViewTransitionDocument = Document & {
  startViewTransition?: (update: () => void) => { finished: Promise<void> };
};

export const reducedMotion = () =>
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

/** Fading copies of earlier rooms kept at once; older ones are dropped. */
const MAX_FADES = 2;

/**
 * The page chrome follows the colour of the room (the album on the platter). The new
 * colours apply at once; a copy of the old room's background is laid just above the new
 * one and fades out on the compositor, so the change reads as a slow cross-fade without
 * restyling or repainting the page on every frame.
 */
export function paintPage(room: string, ink: string) {
  const root = document.documentElement.style;
  const previous = root.getPropertyValue("--page-room").trim();
  if (previous && previous !== room && !reducedMotion() && document.body) {
    const fade = document.createElement("div");
    fade.className = "room-fade";
    fade.setAttribute("aria-hidden", "true");
    fade.style.setProperty("--page-room", previous);
    fade.addEventListener("animationend", () => fade.remove(), { once: true });
    document.body.prepend(fade);
    const fades = document.querySelectorAll(".room-fade");
    for (let i = MAX_FADES; i < fades.length; i++) fades[i]!.remove();
  }
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
