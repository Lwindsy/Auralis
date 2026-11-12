import { useEffect, useRef, useState, type CSSProperties } from "react";
import type { ProductCard } from "../../api/types.ts";
import { genreDisplayName, genreNote } from "../../content/genreNotes.ts";
import { i18n, useCopy } from "../../i18n/index.ts";
import { hash } from "../../lib/cover.ts";

const BARS = 96;
const H = 96;
const RADIUS = 0.26; // lens radius as a share of the width
const POWER = 3.2; // lens magnification
const GAP = 14; // least space between two names on the same side, px
const LIT = 1.16; // scale of the name under the lens

/** Fisheye on one axis: expand near the focus, compress toward the lens edge. */
function lens(x: number, focus: number, power: number) {
  const d = x - focus;
  const ad = Math.abs(d);
  if (ad >= RADIUS || power <= 0.001) return x;
  const k = ad / RADIUS;
  return focus + Math.sign(d) * RADIUS * (((power + 1) * k) / (power * k + 1));
}

/**
 * The catalogue genres as one waveform. Each genre owns a stretch of it; the names take
 * turns below and above the wave so long ones never run into each other, and names on the
 * same side are pushed apart if they would still touch. Moving the pointer along it moves
 * a lens: the local stretch swells and spreads out, its genre lights up, and that genre's
 * short note unfolds below. Without a pointer (or on a keyboard) the tags are also plain
 * buttons that do the same.
 */
export function GenreWave({
  card,
  rootParentGenres,
  still = false,
}: {
  card: ProductCard;
  rootParentGenres: ReadonlySet<string>;
  /** Laid out once and never animated: the leaving copy of an album. */
  still?: boolean;
}) {
  const genres = card.identity.primary_genres;
  const specific = genres.filter((tag) => !rootParentGenres.has(tag.genre_id));
  const tags = (
    specific.length >= 4
      ? specific
      : [
          ...specific,
          ...genres.filter((tag) => rootParentGenres.has(tag.genre_id)),
        ]
  ).slice(0, 4);
  const tagIds = tags.map((tag) => tag.genre_id).join(" ");
  const words = useCopy();
  const locale = i18n.locale;
  const svg = useRef<SVGSVGElement>(null);
  const names = useRef<HTMLDivElement>(null);
  const noteTouch = useRef(false);
  const finger = useRef<{
    id: number;
    x: number;
    y: number;
    sweep: boolean;
  } | null>(null);
  const [active, setActive] = useState<number | null>(null);
  const activeRef = useRef<number | null>(null);
  useEffect(() => {
    activeRef.current = active;
  });
  const target = useRef({ focus: 0.5, power: 0 });
  const seed = hash(card.candidate_id);

  useEffect(() => {
    const root = svg.current;
    if (!root || !tags.length) return;
    const bars = [...root.querySelectorAll<SVGLineElement>(".wave__bar")];
    const labels = [
      ...(names.current?.querySelectorAll<HTMLElement>(".wave__tag") ?? []),
    ];
    // Name widths at rest (scale 1) and the field width, re-measured on resize.
    let widths = labels.map((label) => label.offsetWidth);
    let span = names.current?.clientWidth ?? 0;
    const observer = new ResizeObserver(() => {
      widths = labels.map((label) => label.offsetWidth);
      span = names.current?.clientWidth ?? 0;
    });
    if (names.current) observer.observe(names.current);
    const scales = labels.map(() => 1);
    const heights = bars.map((_, i) => {
      const v = hash(`${seed}:${i}`) / 0xffffffff;
      const segment = Math.floor((i / BARS) * tags.length);
      const local = ((i / BARS) * tags.length) % 1;
      const hump = Math.sin(Math.PI * local);
      return (
        0.1 +
        0.9 *
          (0.35 + 0.65 * v) *
          (0.35 + 0.65 * hump) *
          (segment % 2 ? 0.85 : 1)
      );
    });
    const state = { focus: 0.5, power: 0, t: 0 };
    let frame = 0;
    let last = performance.now();
    const draw = (now: number) => {
      // At rest only the slow breathing moves, so it is drawn at about 24 frames a second;
      // the lens and the names run at full rate while the pointer is on the wave.
      const resting =
        activeRef.current === null &&
        target.current.power === 0 &&
        state.power < 0.002;
      if (resting && now - last < 40) {
        frame = requestAnimationFrame(draw);
        return;
      }
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      state.t += dt;
      const ease = 1 - Math.exp(-dt * 9);
      state.focus += (target.current.focus - state.focus) * ease;
      state.power += (target.current.power - state.power) * ease;
      bars.forEach((bar, i) => {
        const u = (i + 0.5) / BARS;
        const x = lens(u, state.focus, state.power) * 100;
        const near = Math.exp(-(((u - state.focus) / (RADIUS * 0.6)) ** 2));
        const breath = 0.88 + 0.12 * Math.sin(state.t * 2.2 + i * 0.37);
        const h =
          heights[i]! * breath * (1 + 0.9 * near * (state.power / POWER));
        const half = Math.min(H / 2 - 2, (h * H) / 2.6);
        bar.setAttribute("x1", x.toFixed(2));
        bar.setAttribute("x2", x.toFixed(2));
        bar.setAttribute("y1", (H / 2 - half).toFixed(1));
        bar.setAttribute("y2", (H / 2 + half).toFixed(1));
      });
      // Names: ideal centres under the lens, then pushed apart side by side.
      const lit = activeRef.current;
      const lefts = labels.map((_, i) => {
        scales[i]! += ((i === lit ? LIT : 1) - scales[i]!) * ease;
        const u = (i + 0.5) / tags.length;
        const width = widths[i]! * scales[i]!;
        return lens(u, state.focus, state.power) * span - width / 2;
      });
      for (const row of [0, 1]) {
        const members = labels.flatMap((_, i) => (i % 2 === row ? [i] : []));
        for (let n = 1; n < members.length; n++) {
          const a = members[n - 1]!;
          const b = members[n]!;
          lefts[b] = Math.max(
            lefts[b]!,
            lefts[a]! + widths[a]! * scales[a]! + GAP,
          );
        }
        const tail = members[members.length - 1];
        if (tail !== undefined)
          lefts[tail] = Math.min(
            lefts[tail]!,
            span - widths[tail]! * scales[tail]!,
          );
        for (let n = members.length - 2; n >= 0; n--) {
          const a = members[n]!;
          const b = members[n + 1]!;
          lefts[a] = Math.min(
            lefts[a]!,
            lefts[b]! - GAP - widths[a]! * scales[a]!,
          );
        }
        const head = members[0];
        if (head !== undefined) lefts[head] = Math.max(lefts[head]!, 0);
      }
      labels.forEach((label, i) => {
        label.style.transform = `translate3d(${lefts[i]!.toFixed(1)}px,0,0) scale(${scales[i]!.toFixed(3)})`;
      });
      if (!still) frame = requestAnimationFrame(draw);
    };
    if (still) draw(last + 16);
    else frame = requestAnimationFrame(draw);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [seed, tags.length, tagIds, still, locale]);

  if (!tags.length) return null;
  const segmentOf = (u: number) =>
    Math.min(tags.length - 1, Math.max(0, Math.floor(u * tags.length)));
  const focusOn = (index: number | null) => {
    setActive(index);
    target.current =
      index === null
        ? { focus: target.current.focus, power: 0 }
        : { focus: (index + 0.5) / tags.length, power: POWER };
  };
  const tag = active === null ? null : tags[active];
  const note = tag ? (genreNote(tag.genre_id) ?? words.wave.fallback) : null;

  return (
    <div className="wave" data-active={active !== null || undefined}>
      <div
        className="wave__field"
        // A finger has no hover: a tap lights the genre under it (the same genre again
        // puts it out) and it stays lit after the finger lifts; a sideways drag sweeps
        // across the genres. A drag that starts upward or downward is the page scrolling
        // and leaves the wave as it was.
        onPointerDown={(event) => {
          if (event.pointerType !== "touch") return;
          finger.current = {
            id: event.pointerId,
            x: event.clientX,
            y: event.clientY,
            sweep: false,
          };
        }}
        onPointerMove={(event) => {
          if (event.pointerType === "touch") {
            const touch = finger.current;
            if (!touch || touch.id !== event.pointerId) return;
            const dx = Math.abs(event.clientX - touch.x);
            const dy = Math.abs(event.clientY - touch.y);
            if (!touch.sweep) {
              if (dy > 8 && dy >= dx) {
                finger.current = null;
                return;
              }
              if (dx < 8) return;
              touch.sweep = true;
            }
          }
          const box = event.currentTarget.getBoundingClientRect();
          const u = (event.clientX - box.left) / box.width;
          target.current = { focus: u, power: POWER };
          const index = segmentOf(u);
          if (index !== active) setActive(index);
        }}
        onPointerUp={(event) => {
          const touch = finger.current;
          if (!touch || touch.id !== event.pointerId) return;
          finger.current = null;
          if (touch.sweep) return;
          const box = event.currentTarget.getBoundingClientRect();
          const u = (event.clientX - box.left) / box.width;
          const index = segmentOf(u);
          if (index === active) {
            focusOn(null);
            return;
          }
          target.current = { focus: u, power: POWER };
          setActive(index);
        }}
        onPointerCancel={() => {
          finger.current = null;
        }}
        onPointerLeave={(event) => {
          if (event.pointerType !== "touch") focusOn(null);
        }}
      >
        <svg
          ref={svg}
          className="wave__svg"
          viewBox={`0 0 100 ${H}`}
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <line
            className="wave__axis"
            x1="0"
            x2="100"
            y1={H / 2}
            y2={H / 2}
            vectorEffect="non-scaling-stroke"
          />
          {Array.from({ length: BARS }, (_, i) => (
            <line
              key={i}
              className="wave__bar"
              vectorEffect="non-scaling-stroke"
              data-on={
                active !== null && segmentOf((i + 0.5) / BARS) === active
                  ? true
                  : undefined
              }
              style={{ "--i": i } as CSSProperties}
            />
          ))}
        </svg>
        <div className="wave__tags" ref={names} aria-hidden="true">
          {tags.map((item, i) => (
            <span
              key={item.genre_id}
              className="wave__tag"
              data-row={i % 2 ? "top" : "bottom"}
              data-on={active === i || undefined}
            >
              {genreDisplayName(item.genre_id, item.name, locale)}
            </span>
          ))}
        </div>
      </div>
      <ul className="wave__keys" role="list" aria-label={words.wave.tags}>
        {tags.map((item, i) => (
          <li key={item.genre_id}>
            <button
              type="button"
              onFocus={() => focusOn(i)}
              onBlur={() => focusOn(null)}
              onClick={() => focusOn(active === i ? null : i)}
            >
              {genreDisplayName(item.genre_id, item.name, locale)}
            </button>
          </li>
        ))}
      </ul>
      {/* On a touch screen a tap on the note puts it away, as a tap on its genre does. */}
      <div
        className="wave__note"
        aria-live="polite"
        onPointerDown={(event) => {
          noteTouch.current = event.pointerType === "touch";
        }}
        onClick={() => {
          if (noteTouch.current) focusOn(null);
        }}
      >
        {tag && note ? (
          <p key={tag.genre_id}>
            <strong>{genreDisplayName(tag.genre_id, tag.name, locale)}</strong>
            {[...note].map((char, i) => (
              <span
                key={i}
                className="typed"
                style={{ "--t": `${i * 9}ms` } as CSSProperties}
              >
                {char}
              </span>
            ))}
          </p>
        ) : null}
      </div>
    </div>
  );
}
