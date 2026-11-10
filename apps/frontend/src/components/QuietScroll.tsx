import { useEffect, useRef, type PointerEvent, type ReactNode } from "react";

/** Shortest thumb, px. */
const MIN_THUMB = 28;
/** The thumb stays this long after the last scroll before fading out. */
const LINGER_MS = 900;

/**
 * A scroll area with no visible scrollbar. The browser's own bar is switched off
 * completely (some systems ignore colour hints and would still draw a track), and a slim
 * thumb is drawn instead. It appears only while the pointer is over the area, while it
 * is scrolling, or while it is being dragged; the track behind it is never drawn. Wheel,
 * touch and keyboard scrolling are the browser's own and unchanged.
 *
 * The first child is the element that scrolls; it keeps its own classes and layout.
 */
export function QuietScroll({
  className,
  children,
}: {
  className?: string;
  children: ReactNode;
}) {
  const frame = useRef<HTMLDivElement>(null);
  const thumb = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    const box = frame.current;
    const bar = thumb.current;
    const scroller = box?.firstElementChild as HTMLElement | null;
    if (!box || !bar || !scroller) return;
    let size = MIN_THUMB;
    let raf = 0;
    let linger: ReturnType<typeof setTimeout> | undefined;
    const place = () => {
      raf = 0;
      const view = scroller.clientHeight;
      const full = scroller.scrollHeight;
      const room = full - view;
      if (room <= 1 || view <= 0) {
        box.removeAttribute("data-overflow");
        return;
      }
      box.setAttribute("data-overflow", "");
      size = Math.max(MIN_THUMB, (view * view) / full);
      const top =
        scroller.offsetTop + (scroller.scrollTop / room) * (view - size);
      bar.style.height = `${size}px`;
      bar.style.transform = `translate3d(0, ${top}px, 0)`;
    };
    const schedule = () => {
      if (!raf) raf = requestAnimationFrame(place);
    };
    const onScroll = () => {
      schedule();
      box.setAttribute("data-scrolling", "");
      clearTimeout(linger);
      linger = setTimeout(
        () => box.removeAttribute("data-scrolling"),
        LINGER_MS,
      );
    };
    // Lists grow as pages load and branches open: watch the content as well as the box.
    const resize = new ResizeObserver(schedule);
    resize.observe(scroller);
    for (const child of scroller.children) resize.observe(child);
    const mutate = new MutationObserver(() => {
      for (const child of scroller.children) resize.observe(child);
      schedule();
    });
    mutate.observe(scroller, { childList: true, subtree: true });
    scroller.addEventListener("scroll", onScroll, { passive: true });
    place();
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(linger);
      resize.disconnect();
      mutate.disconnect();
      scroller.removeEventListener("scroll", onScroll);
    };
  }, []);

  // Dragging the thumb scrolls the area in proportion.
  const drag = useRef<{ y: number; top: number } | null>(null);
  const scroller = () =>
    frame.current?.firstElementChild as HTMLElement | null | undefined;
  const onDown = (event: PointerEvent<HTMLSpanElement>) => {
    const area = scroller();
    if (!area || event.button !== 0) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { y: event.clientY, top: area.scrollTop };
    frame.current?.setAttribute("data-dragging", "");
  };
  const onMove = (event: PointerEvent<HTMLSpanElement>) => {
    const area = scroller();
    const start = drag.current;
    if (!area || !start) return;
    const view = area.clientHeight;
    const room = area.scrollHeight - view;
    const size = Math.max(MIN_THUMB, (view * view) / area.scrollHeight);
    const travel = Math.max(1, view - size);
    area.scrollTop = start.top + ((event.clientY - start.y) / travel) * room;
  };
  const onUp = (event: PointerEvent<HTMLSpanElement>) => {
    if (!drag.current) return;
    drag.current = null;
    frame.current?.removeAttribute("data-dragging");
    if (event.currentTarget.hasPointerCapture(event.pointerId))
      event.currentTarget.releasePointerCapture(event.pointerId);
  };

  return (
    <div ref={frame} className={["quiet", className ?? ""].join(" ")}>
      {children}
      <span
        ref={thumb}
        className="quiet__thumb"
        aria-hidden="true"
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerCancel={onUp}
      />
    </div>
  );
}
