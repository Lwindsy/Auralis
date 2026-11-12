import { useLayoutEffect, useRef } from "react";

/** Line height of the title, as in deck.css. */
const LEADING = 1.04;
/** The smallest the title may get, as a share of its full size. */
const FLOOR = 0.6;

/**
 * The album name in a fixed slot under the sleeve: as wide as the cover and two lines of
 * the full size tall, so a long name never moves the cover or the words beside it. A name
 * that needs more than two lines steps down in size until it fits (at the smallest, about
 * three lines fill the same slot); only a name that still does not fit is cut with an
 * ellipsis, and then shows in full on hover.
 */
export function AlbumTitle({ title }: { title: string }) {
  const ref = useRef<HTMLHeadingElement>(null);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const fit = () => {
      element.style.fontSize = "";
      element.style.removeProperty("-webkit-line-clamp");
      delete element.dataset.clamped;
      element.removeAttribute("title");
      const full = parseFloat(getComputedStyle(element).fontSize);
      if (!full) return;
      const room = 2 * LEADING * full + 1;
      const smallest = Math.max(20, full * FLOOR);
      let size = full;
      while (element.scrollHeight > room && size > smallest) {
        size = Math.max(smallest, size * 0.94);
        element.style.fontSize = `${size.toFixed(1)}px`;
      }
      if (element.scrollHeight > room) {
        element.dataset.clamped = "";
        element.style.setProperty(
          "-webkit-line-clamp",
          String(Math.max(2, Math.floor(room / (LEADING * size)))),
        );
        element.title = title;
      }
    };
    fit();
    // Refit when the slot gets wider or narrower (the cover follows the window) and once
    // the web font has arrived; height changes are the fit's own doing and are ignored.
    let width = element.clientWidth;
    const observer = new ResizeObserver(() => {
      if (element.clientWidth === width) return;
      width = element.clientWidth;
      fit();
    });
    observer.observe(element);
    let alive = true;
    void document.fonts?.ready.then(() => {
      if (alive) fit();
    });
    return () => {
      alive = false;
      observer.disconnect();
    };
  }, [title]);

  return (
    <h2 ref={ref} className="album-title">
      {title}
    </h2>
  );
}
