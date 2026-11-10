import { useEffect, useRef, useState, type CSSProperties } from "react";
import type { ProductCard } from "../../api/types.ts";
import { genreNote } from "../../content/genreNotes.ts";
import { hash } from "../../lib/cover.ts";

const BARS = 96;
const H = 96;
const RADIUS = 0.26; // lens radius as a share of the width
const POWER = 3.2; // lens magnification

/** Fisheye on one axis: expand near the focus, compress toward the lens edge. */
function lens(x: number, focus: number, power: number) {
  const d = x - focus;
  const ad = Math.abs(d);
  if (ad >= RADIUS || power <= 0.001) return x;
  const k = ad / RADIUS;
  return focus + Math.sign(d) * RADIUS * (((power + 1) * k) / (power * k + 1));
}

/**
 * The catalogue genres as one waveform. Each genre owns a stretch of it, labelled
 * underneath. Moving the pointer along it moves a lens: the local stretch swells and
 * spreads out, its genre lights up, and that genre's short note unfolds below. Without a
 * pointer (or on a keyboard) the tags are also plain buttons that do the same.
 */
export function GenreWave({ card }: { card: ProductCard }) {
  const tags = card.identity.primary_genres.slice(0, 4);
  const svg = useRef<SVGSVGElement>(null);
  const names = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState<number | null>(null);
  const target = useRef({ focus: 0.5, power: 0 });
  const seed = hash(card.candidate_id);

  useEffect(() => {
    const root = svg.current;
    if (!root || !tags.length) return;
    const bars = [...root.querySelectorAll<SVGLineElement>(".wave__bar")];
    const labels = [
      ...(names.current?.querySelectorAll<HTMLElement>(".wave__tag") ?? []),
    ];
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
      labels.forEach((label, i) => {
        const u = (i + 0.5) / tags.length;
        label.style.left = `${(lens(u, state.focus, state.power) * 100).toFixed(2)}%`;
      });
      frame = requestAnimationFrame(draw);
    };
    frame = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(frame);
  }, [seed, tags.length]);

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
  const note = tag
    ? (genreNote(tag.name) ?? "音乐目录给这张专辑标的风格，只是线索。")
    : null;

  return (
    <div className="wave" data-active={active !== null || undefined}>
      <div
        className="wave__field"
        onPointerMove={(event) => {
          const box = event.currentTarget.getBoundingClientRect();
          const u = (event.clientX - box.left) / box.width;
          target.current = { focus: u, power: POWER };
          const index = segmentOf(u);
          if (index !== active) setActive(index);
        }}
        onPointerLeave={() => focusOn(null)}
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
              data-on={active === i || undefined}
              style={{ left: `${((i + 0.5) / tags.length) * 100}%` }}
            >
              {item.name}
            </span>
          ))}
        </div>
      </div>
      <ul className="wave__keys" role="list" aria-label="目录风格标签">
        {tags.map((item, i) => (
          <li key={item.genre_id}>
            <button
              type="button"
              onFocus={() => focusOn(i)}
              onBlur={() => focusOn(null)}
              onClick={() => focusOn(active === i ? null : i)}
            >
              {item.name}
            </button>
          </li>
        ))}
      </ul>
      <div className="wave__note" aria-live="polite">
        {tag && note ? (
          <p key={tag.genre_id}>
            <strong>{tag.name}</strong>
            {[...note].map((char, i) => (
              <span
                key={i}
                className="type"
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
