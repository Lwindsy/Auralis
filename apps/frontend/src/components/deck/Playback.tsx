import {
  Fragment,
  useEffect,
  useMemo,
  useRef,
  type KeyboardEvent,
  type PointerEvent,
} from "react";

const MARKER = /\[(R[1-9][0-9]*)\]/g;
/** Characters per second used for the "length" shown at the end of the line. */
const READ_RATE = 7;

type Part = string | { marker: string };

function split(text: string): Part[] {
  const parts: Part[] = [];
  let last = 0;
  for (const match of text.matchAll(MARKER)) {
    if (match.index > last) parts.push(text.slice(last, match.index));
    parts.push({ marker: match[1] ?? "" });
    last = match.index + match[0].length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}

export const charCount = (text: string) =>
  split(text).reduce(
    (sum, part) => sum + (typeof part === "string" ? [...part].length : 1),
    0,
  );

const clock = (seconds: number) =>
  `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;

const reduced = () =>
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;

interface Engine {
  play: () => void;
  pause: () => void;
  /** Jump to a share of the text (0–1). */
  seek: (share: number) => void;
  share: () => number;
  playing: () => boolean;
  started: boolean;
}

/**
 * A piece of writing played like a track: a thin progress line with a playhead runs left
 * to right and the words appear exactly as far as it has travelled. The playhead can be
 * grabbed and dragged freely, back or forth; the words follow it at once, and letting go
 * plays on from there. Arrow keys move it too. The whole text is in the DOM throughout
 * (hidden characters are only transparent), so it is read in full by assistive technology.
 *
 * One clock drives everything from script and only the characters that change are
 * touched each frame, so long reviews stay light.
 */
export function Playback({
  text,
  pace = 18,
  label,
  className,
  autoplay = false,
  startDelay = 0,
  onEnd,
}: {
  text: string;
  /** Milliseconds per character. */
  pace?: number;
  label?: string;
  className?: string;
  /** Start playing (once) when this turns true, after `startDelay` ms. */
  autoplay?: boolean;
  startDelay?: number;
  /** Called the first time the track reaches its end. */
  onEnd?: () => void;
}) {
  const parts = useMemo(() => split(text), [text]);
  const total = charCount(text);
  const root = useRef<HTMLDivElement>(null);
  const engine = useRef<Engine | null>(null);
  const ended = useRef(onEnd);
  useEffect(() => {
    ended.current = onEnd;
  });

  useEffect(() => {
    const element = root.current;
    if (!element) return;
    const chars = [...element.querySelectorAll<HTMLElement>(".type")];
    const count = chars.length;
    let shown = 0;
    let position = 0;
    let running = false;
    let finished = false;
    let last = 0;
    let frame = 0;
    const render = (next: number) => {
      position = Math.max(0, Math.min(count, next));
      const reach = Math.min(count, Math.floor(position + 1e-6));
      while (shown < reach) chars[shown++]!.classList.add("on");
      while (shown > reach) chars[--shown]!.classList.remove("on");
      element.style.setProperty(
        "--q",
        count ? (position / count).toFixed(4) : "1",
      );
      element.dataset.state =
        position >= count ? "done" : running ? "playing" : "paused";
    };
    const tick = (now: number) => {
      render(position + (now - last) / pace);
      last = now;
      if (position >= count) {
        running = false;
        render(position);
        if (!finished) {
          finished = true;
          ended.current?.();
        }
        return;
      }
      frame = requestAnimationFrame(tick);
    };
    const control: Engine = {
      started: false,
      play: () => {
        control.started = true;
        if (running || position >= count) return;
        running = true;
        last = performance.now();
        frame = requestAnimationFrame(tick);
      },
      pause: () => {
        running = false;
        cancelAnimationFrame(frame);
        render(position);
      },
      seek: (share) => render(share * count),
      share: () => (count ? position / count : 1),
      playing: () => running,
    };
    engine.current = control;
    if (reduced()) {
      control.started = true;
      render(count);
      finished = true;
    } else render(0);
    return () => {
      cancelAnimationFrame(frame);
      engine.current = null;
    };
  }, [parts, pace]);

  useEffect(() => {
    if (!autoplay) return;
    const timer = setTimeout(() => {
      const control = engine.current;
      if (control && !control.started) control.play();
    }, startDelay);
    return () => clearTimeout(timer);
  }, [autoplay, startDelay, parts]);

  const dragging = useRef(false);
  const shareAt = (event: PointerEvent<HTMLElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    return Math.max(0, Math.min(1, (event.clientX - box.left) / box.width));
  };
  const onDown = (event: PointerEvent<HTMLElement>) => {
    if (event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    dragging.current = true;
    root.current?.setAttribute("data-scrubbing", "");
    engine.current?.pause();
    engine.current?.seek(shareAt(event));
  };
  const onMove = (event: PointerEvent<HTMLElement>) => {
    if (dragging.current) engine.current?.seek(shareAt(event));
  };
  const onUp = (event: PointerEvent<HTMLElement>) => {
    if (!dragging.current) return;
    dragging.current = false;
    root.current?.removeAttribute("data-scrubbing");
    if (event.currentTarget.hasPointerCapture(event.pointerId))
      event.currentTarget.releasePointerCapture(event.pointerId);
    engine.current?.play();
  };
  const onKey = (event: KeyboardEvent<HTMLElement>) => {
    const control = engine.current;
    if (!control) return;
    const step = event.shiftKey ? 0.2 : 0.05;
    const moves: Record<string, number> = {
      ArrowLeft: control.share() - step,
      ArrowDown: control.share() - step,
      ArrowRight: control.share() + step,
      ArrowUp: control.share() + step,
      Home: 0,
      End: 1,
    };
    const target = moves[event.key];
    if (target === undefined) return;
    event.preventDefault();
    control.pause();
    control.seek(Math.max(0, Math.min(1, target)));
    control.play();
  };

  return (
    <div
      ref={root}
      className={["track", className ?? ""].join(" ")}
      data-state="paused"
    >
      <div className="track__head">
        {label ? <span className="track__label mono">{label}</span> : null}
        <div
          className="track__line"
          role="slider"
          tabIndex={0}
          aria-label={`${label ?? "评论"}播放进度，可拖动`}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuetext="拖动或用方向键调整阅读进度"
          onPointerDown={onDown}
          onPointerMove={onMove}
          onPointerUp={onUp}
          onPointerCancel={onUp}
          onKeyDown={onKey}
        >
          <span className="track__fill" />
          <span className="track__head-dot" />
        </div>
        <span className="track__time mono">
          {clock(Math.max(1, Math.round(total / READ_RATE)))}
        </span>
      </div>
      <p className="track__text">
        {parts.map((part, i) =>
          typeof part === "string" ? (
            <Fragment key={i}>
              {[...part].map((char, j) => (
                <span key={j} className="type">
                  {char}
                </span>
              ))}
            </Fragment>
          ) : (
            <sup key={i} className="cite type" title={`资料 ${part.marker}`}>
              {part.marker}
            </sup>
          ),
        )}
      </p>
    </div>
  );
}
