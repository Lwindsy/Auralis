import { useLayoutEffect, type RefObject } from "react";

/** Top of the header the album moves into, px from the top of the window. */
const HEAD_TOP = 82;
const THUMB = 84;
const TITLE_PX = 28;
const WAVE_SCALE = 0.6;
const GAP = 24;
/** The foot row (reactions and the review toggle) stays this far above the window's
 *  bottom edge, clear of the position pips. */
const FOOT_CLEAR = 78;
const TEXT_MAX = 700;

/** How long the sheet takes to go back down (matches the closing transition in CSS). */
export const LINER_CLOSE_MS = 640;

/**
 * Liner notes: once the long review is ready, a sheet rises from the bottom like the notes
 * pulled out of a sleeve. The sleeve and title move up into a header, the genre wave to
 * its right, and the three reactions with the review toggle ride down onto the sheet's
 * foot; the short review stays where it was, under the sheet.
 *
 * Nothing re-flows. Every piece keeps its place in the layout and is only moved with a
 * transform measured here (while it is at rest), so the album never shifts and the whole
 * change runs on the compositor. The background keeps moving: the sheet only covers it.
 * Measurements are redone, without animation, when the window changes size.
 */
export function useLiner(anchor: RefObject<HTMLElement | null>, open: boolean) {
  useLayoutEffect(() => {
    const cut = anchor.current?.closest<HTMLElement>(".cut");
    if (!cut || !open) return;
    const place = (animate: boolean) => {
      if (!animate) cut.setAttribute("data-liner-still", "");
      cut.removeAttribute("data-reading");
      const vars = measure(cut);
      if (!vars) return;
      for (const [name, value] of Object.entries(vars))
        cut.style.setProperty(name, value);
      // Commit the resting state first so the change below is a transition from it.
      void cut.offsetWidth;
      cut.setAttribute("data-reading", "");
      if (!animate) {
        void cut.offsetWidth;
        cut.removeAttribute("data-liner-still");
      }
    };
    // The page itself holds still under the sheet (the sheet scrolls on its own), so
    // the header and the sheet, measured against the window, stay together.
    const root = document.documentElement;
    const overflow = root.style.overflow;
    root.style.overflow = "hidden";
    place(true);
    let frame = 0;
    const onResize = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => place(false));
    };
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
      cancelAnimationFrame(frame);
      cut.removeAttribute("data-reading");
      root.style.overflow = overflow;
    };
  }, [anchor, open]);
}

const px = (value: number) => `${value.toFixed(1)}px`;
const move = (x: number, y: number, scale = 1) =>
  `translate(${px(x)}, ${px(y)}) scale(${scale.toFixed(4)})`;

function measure(cut: HTMLElement): Record<string, string> | null {
  const find = (selector: string) => cut.querySelector<HTMLElement>(selector);
  const cover = find(".sliced");
  const caption = find(".cut__caption");
  const title = find(".album-title");
  const wave = find(".wave");
  const reacts = find(".reacts");
  const bar = find(".reviews__bar");
  const toggle = find(".reviews__toggle");
  const stage = cut.closest<HTMLElement>(".deck__stage");
  if (
    !cover ||
    !caption ||
    !title ||
    !wave ||
    !reacts ||
    !bar ||
    !toggle ||
    !stage
  )
    return null;
  const c = cover.getBoundingClientRect();
  const cap = caption.getBoundingClientRect();
  const w = wave.getBoundingClientRect();
  const re = reacts.getBoundingClientRect();
  const b = bar.getBoundingClientRect();
  const tg = toggle.getBoundingClientRect();
  const st = stage.getBoundingClientRect();
  const height = window.innerHeight;
  const stacked = window.innerWidth <= 1100; // one column: the words sit under the sleeve
  const small = window.innerWidth <= 760;
  const thumb = small ? 60 : THUMB;
  const top = small ? 66 : HEAD_TOP;
  const inset = small ? 18 : GAP;
  const left = stacked ? Math.max(w.left, st.left + inset) : c.left;
  const right = w.right;

  const coverScale = thumb / c.width;
  const fontSize = parseFloat(getComputedStyle(title).fontSize) || 48;
  const titleScale = Math.min(1, (small ? 20 : TITLE_PX) / fontSize);
  const captionX = left + thumb + (small ? 16 : GAP);
  const captionY = top + (thumb - cap.height * titleScale) / 2;
  const waveX = right - w.width * WAVE_SCALE;
  const waveY = top + (thumb - w.height * WAVE_SCALE) / 2;
  // The wave gives way when the title would run into it.
  const waveFits = !small && captionX + cap.width * titleScale < waveX - GAP;

  const sheetTop = top + thumb + (small ? 20 : 30);
  // The text keeps a margin inside the sheet even where the column runs edge to edge.
  const textLeft = Math.max(stacked ? left : captionX, st.left + inset);
  const textWidth = Math.min(TEXT_MAX, st.right - inset - textLeft);
  const footTop = height - FOOT_CLEAR - re.height;
  const barX = textLeft + textWidth - b.width;
  // The toggle sits at the right end of the reactions' row (centred on their 40px
  // buttons), or just above it where the row has no room left.
  const lastReact = reacts.lastElementChild?.getBoundingClientRect();
  const used = lastReact ? lastReact.right - re.left : re.width;
  const beside = used + GAP + tg.width <= textWidth;
  const toggleMiddle = beside
    ? footTop + re.height - 20
    : footTop - tg.height / 2 - 6;
  const barY = toggleMiddle - (tg.top + tg.height / 2 - b.top);
  const textBottom = beside ? footTop - 18 : toggleMiddle - tg.height / 2 - 14;
  return {
    "--to-cover": move(left - c.left, top - c.top, coverScale),
    "--to-caption": move(captionX - cap.left, captionY - cap.top, titleScale),
    "--to-wave": move(waveX - w.left, waveY - w.top, WAVE_SCALE),
    "--wave-o": waveFits ? "1" : "0",
    "--to-reacts": move(textLeft - re.left, footTop - re.top),
    "--to-bar": move(barX - b.left, barY - b.top),
    "--liner-top": px(sheetTop),
    "--liner-left": px(st.left),
    "--liner-right": px(window.innerWidth - st.right),
    "--liner-text-left": px(textLeft),
    "--liner-text-width": px(textWidth),
    "--liner-text-bottom": px(height - textBottom),
  };
}
