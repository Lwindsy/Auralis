import { useEffect, useRef } from "react";

/**
 * A topographic map of sound. A smooth scalar field — a few slow "hills" drifting on
 * Lissajous paths plus a faint swell — is traced at fixed heights with marching squares,
 * so the page is covered in contour lines that breathe and drift. Every fifth line is an
 * index contour, drawn heavier, as on a survey map.
 *
 * The terrain is never the same twice: hills are seeded at random on each load, their
 * pace wanders, and now and then a stray gust drops a faint ripple somewhere. Switching
 * albums drops a ripple at the sleeve. The exploration mode reshapes the land: 熟悉 pulls
 * it into tight rings around the centre, 拓展 lets it unfold, 惊喜 raises long ridges that
 * run off the edge of the screen.
 */

interface Ripple {
  x: number;
  y: number;
  start: number;
  strength: number;
}

export type Terrain = "familiar" | "explore" | "surprise";

/** Shape parameters per mode: focus (centred rings), spread (how far hills roam), relief. */
const TERRAIN: Record<
  Terrain,
  { focus: number; spread: number; relief: number }
> = {
  familiar: { focus: 1, spread: 0.55, relief: 0.7 },
  explore: { focus: 0, spread: 1, relief: 1 },
  surprise: { focus: 0, spread: 1.7, relief: 1.55 },
};

const field = {
  flow: 0.5,
  target: 0.5,
  ripples: [] as Ripple[],
  shape: { ...TERRAIN.explore },
  goal: { ...TERRAIN.explore },
};

/** Reshape the land for an exploration mode; the change eases in over a few seconds. */
export function setTerrain(mode: Terrain) {
  field.goal = { ...TERRAIN[mode] };
}

/** Push a ring through the map from a point on screen (defaults to the centre). */
export function ripple(strength = 1, x?: number, y?: number) {
  field.ripples.push({
    x: x ?? window.innerWidth / 2,
    y: y ?? window.innerHeight / 2,
    start: performance.now(),
    strength,
  });
  if (field.ripples.length > 4) field.ripples.shift();
}

/** How fast the terrain drifts: quiet while reading, restless while waiting. */
export function setFlow(level: number) {
  field.target = level;
}

const LEVELS = 15;
const CELL = 26;
const FPS = 30;

const reduced = () =>
  typeof window !== "undefined" &&
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

interface Hill {
  ax: number;
  ay: number;
  fx: number;
  fy: number;
  phase: number;
  size: number;
  height: number;
}

const between = (low: number, high: number) =>
  low + Math.random() * (high - low);

/** A fresh landscape for every visit. */
function seedHills(): Hill[] {
  const count = 5 + Math.floor(Math.random() * 3);
  return Array.from({ length: count }, (_, i) => ({
    ax: between(0.18, 0.42),
    ay: between(0.16, 0.36),
    fx: between(0.03, 0.1),
    fy: between(0.03, 0.09),
    phase: between(0, Math.PI * 2),
    size: between(0.18, 0.42),
    height: (i % 3 === 2 ? -1 : 1) * between(0.45, 1),
  }));
}

export function Contours() {
  const ref = useRef<SVGSVGElement>(null);
  useEffect(() => {
    const svg = ref.current;
    if (!svg) return;
    const paths = [...svg.querySelectorAll<SVGPathElement>("path")];
    let width = 0;
    let height = 0;
    let cols = 0;
    let rows = 0;
    let values = new Float32Array(0);
    const resize = () => {
      width = window.innerWidth;
      height = window.innerHeight;
      cols = Math.ceil(width / CELL) + 1;
      rows = Math.ceil(height / CELL) + 1;
      values = new Float32Array((cols + 1) * (rows + 1));
      svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
    };
    resize();
    const hills = seedHills();
    const swellPhase = between(0, 100);

    const sample = (clock: number, now: number) => {
      const scale = Math.max(width, height);
      const { focus, spread, relief } = field.shape;
      const centres = hills.map((h) => ({
        x: width * (0.5 + h.ax * spread * Math.sin(clock * h.fx + h.phase)),
        y:
          height *
          (0.5 + h.ay * spread * Math.cos(clock * h.fy + h.phase * 1.3)),
        s2: (h.size * scale * (0.7 + 0.5 * spread)) ** 2,
        h: h.height * relief * (1 - 0.75 * focus),
      }));
      // 熟悉: one broad hill held at the centre draws tight concentric rings.
      if (focus > 0.01)
        centres.push({
          x: width * (0.5 + 0.03 * Math.sin(clock * 0.2)),
          y: height * (0.48 + 0.03 * Math.cos(clock * 0.17)),
          s2: (0.3 * scale) ** 2,
          h: 1.9 * focus,
        });
      const waves = field.ripples.map((r) => ({
        ...r,
        radius: ((now - r.start) / 1000) * 520,
        fade: Math.exp(-(now - r.start) / 1400),
      }));
      for (let j = 0; j <= rows; j++) {
        const y = j * CELL;
        for (let i = 0; i <= cols; i++) {
          const x = i * CELL;
          let v = 0;
          for (const c of centres) {
            const dx = x - c.x;
            const dy = y - c.y;
            v += c.h * Math.exp(-(dx * dx + dy * dy) / c.s2);
          }
          v +=
            0.18 *
            relief *
            Math.sin(x * (0.0032 / spread) + clock * 0.11 + swellPhase) *
            Math.cos(y * (0.0041 / spread) - clock * 0.08);
          for (const w of waves) {
            const d = Math.hypot(x - w.x, y - w.y) - w.radius;
            v += 0.32 * w.strength * w.fade * Math.exp(-(d * d) / 5200);
          }
          values[j * (cols + 1) + i] = v;
        }
      }
    };

    const trace = () => {
      const w = cols + 1;
      for (let l = 0; l < LEVELS; l++) {
        const level = -0.75 + (l * 1.9) / (LEVELS - 1);
        let d = "";
        for (let j = 0; j < rows; j++) {
          for (let i = 0; i < cols; i++) {
            const a = values[j * w + i]!;
            const b = values[j * w + i + 1]!;
            const c = values[(j + 1) * w + i + 1]!;
            const e = values[(j + 1) * w + i]!;
            const index =
              (a > level ? 8 : 0) |
              (b > level ? 4 : 0) |
              (c > level ? 2 : 0) |
              (e > level ? 1 : 0);
            if (index === 0 || index === 15) continue;
            const x = i * CELL;
            const y = j * CELL;
            const lerp = (p: number, q: number) =>
              (level - p) / (q - p || 1e-6);
            const top = () => `${(x + lerp(a, b) * CELL).toFixed(1)} ${y}`;
            const right = () =>
              `${x + CELL} ${(y + lerp(b, c) * CELL).toFixed(1)}`;
            const bottom = () =>
              `${(x + lerp(e, c) * CELL).toFixed(1)} ${y + CELL}`;
            const left = () => `${x} ${(y + lerp(a, e) * CELL).toFixed(1)}`;
            const seg = (p: string, q: string) => {
              d += `M${p}L${q}`;
            };
            switch (index) {
              case 1:
              case 14:
                seg(left(), bottom());
                break;
              case 2:
              case 13:
                seg(bottom(), right());
                break;
              case 3:
              case 12:
                seg(left(), right());
                break;
              case 4:
              case 11:
                seg(top(), right());
                break;
              case 5:
                seg(left(), top());
                seg(bottom(), right());
                break;
              case 6:
              case 9:
                seg(top(), bottom());
                break;
              case 7:
              case 8:
                seg(left(), top());
                break;
              case 10:
                seg(left(), bottom());
                seg(top(), right());
                break;
            }
          }
        }
        paths[l]?.setAttribute("d", d);
      }
    };

    let clock = 20;
    if (reduced()) {
      sample(clock, performance.now());
      trace();
      return;
    }
    let frame = 0;
    let last = performance.now();
    let lastDraw = 0;
    let gustAt = performance.now() + between(4000, 9000);
    const loop = (now: number) => {
      const dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      field.flow += (field.target - field.flow) * (1 - Math.exp(-dt * 1.2));
      const ease = 1 - Math.exp(-dt * 0.9);
      field.shape.focus += (field.goal.focus - field.shape.focus) * ease;
      field.shape.spread += (field.goal.spread - field.shape.spread) * ease;
      field.shape.relief += (field.goal.relief - field.shape.relief) * ease;
      // The pace itself wanders a little, so the drift never feels mechanical.
      const wander = 1 + 0.35 * Math.sin(now / 7300) * Math.sin(now / 3100);
      clock += dt * field.flow * wander;
      if (now > gustAt) {
        gustAt = now + between(6000, 14000);
        field.ripples.push({
          x: between(0, width),
          y: between(0, height),
          start: now,
          strength: between(0.2, 0.5),
        });
      }
      field.ripples = field.ripples.filter((r) => now - r.start < 4200);
      if (now - lastDraw >= 1000 / FPS) {
        lastDraw = now;
        sample(clock, now);
        trace();
      }
      frame = requestAnimationFrame(loop);
    };
    const start = () => {
      cancelAnimationFrame(frame);
      last = performance.now();
      frame = requestAnimationFrame(loop);
    };
    const visibility = () =>
      document.hidden ? cancelAnimationFrame(frame) : start();
    document.addEventListener("visibilitychange", visibility);
    window.addEventListener("resize", resize);
    start();
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("visibilitychange", visibility);
      window.removeEventListener("resize", resize);
    };
  }, []);

  return (
    <svg
      ref={ref}
      className="contours"
      aria-hidden="true"
      focusable="false"
      preserveAspectRatio="none"
    >
      {Array.from({ length: LEVELS }, (_, i) => (
        <path
          key={i}
          className={
            i % 5 === 2
              ? "contours__line contours__line--index"
              : "contours__line"
          }
        />
      ))}
    </svg>
  );
}
