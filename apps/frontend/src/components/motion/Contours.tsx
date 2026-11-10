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
 *
 * Drawn on a canvas every frame: crossings are chained into whole lines and traced with
 * quadratic curves through their midpoints, so the map stays smooth and runs at the
 * display's own rate (it drops to every other frame only if a frame gets expensive).
 *
 * The pointer is a hand passing through the lines. Under the map lies an elastic membrane
 * (a damped spring at every grid point, coupled to its neighbours): a fast sweep pushes
 * the ground up ahead of the hand and down behind it, the disturbance spreads a little and
 * the springs keep pulling everything back to rest. A slow hand barely stirs it; stop and
 * the lines settle back where they were.
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

/* Membrane: spring back to rest, coupling to neighbours, friction. */
const SPRING = 7;
const COUPLING = 16;
const FRICTION = 2.6;
/** Hand radius in px and the speed (px/s) at which it disturbs at full strength. */
const HAND = 150;
const FULL_SPEED = 1600;

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
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context) return;
    const ctx = context;
    let width = 0;
    let height = 0;
    let cols = 0;
    let rows = 0;
    let ratio = 1;
    let values = new Float32Array(0);
    let lift = new Float32Array(0);
    let speed = new Float32Array(0);
    let spare = new Float32Array(0);
    // Marching-squares bookkeeping: two neighbours per grid edge, a visit stamp, and the
    // edges touched by the current level.
    let near1 = new Int32Array(0);
    let near2 = new Int32Array(0);
    let stamp = new Int32Array(0);
    let touched = new Int32Array(0);
    let chain = new Int32Array(0);
    let visit = 0;
    const resize = () => {
      width = window.innerWidth;
      height = window.innerHeight;
      ratio = Math.min(2, window.devicePixelRatio || 1);
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
      cols = Math.ceil(width / CELL) + 1;
      rows = Math.ceil(height / CELL) + 1;
      const size = (cols + 1) * (rows + 1);
      values = new Float32Array(size);
      lift = new Float32Array(size);
      speed = new Float32Array(size);
      spare = new Float32Array(size);
      near1 = new Int32Array(size * 2);
      near2 = new Int32Array(size * 2);
      stamp = new Int32Array(size * 2);
      touched = new Int32Array(size * 2);
      chain = new Int32Array(size * 2 + 1);
      visit = 0;
    };

    // The hand: last position and a smoothed velocity in px/s.
    const hand = { x: -1, y: -1, vx: 0, vy: 0, t: 0, active: false };
    const onMove = (event: PointerEvent) => {
      const now = performance.now();
      if (hand.active) {
        const dt = Math.max(8, now - hand.t) / 1000;
        const vx = (event.clientX - hand.x) / dt;
        const vy = (event.clientY - hand.y) / dt;
        hand.vx += (vx - hand.vx) * 0.5;
        hand.vy += (vy - hand.vy) * 0.5;
      }
      hand.x = event.clientX;
      hand.y = event.clientY;
      hand.t = now;
      hand.active = true;
    };
    const onLeave = () => {
      hand.active = false;
      hand.vx = 0;
      hand.vy = 0;
    };

    /** Advance the membrane by `dt` seconds and let the hand push it. */
    const stir = (dt: number, now: number) => {
      const w = cols + 1;
      // The hand's push fades if the pointer has not moved for a moment.
      const idle = now - hand.t;
      if (idle > 60) {
        const fade = Math.exp(-dt * 10);
        hand.vx *= fade;
        hand.vy *= fade;
      }
      const v = Math.hypot(hand.vx, hand.vy);
      const strength = Math.min(1, v / FULL_SPEED) ** 1.4;
      if (hand.active && strength > 0.01) {
        const r = Math.ceil(HAND / CELL) + 1;
        const ci = Math.round(hand.x / CELL);
        const cj = Math.round(hand.y / CELL);
        const ux = hand.vx / v;
        const uy = hand.vy / v;
        for (let j = Math.max(0, cj - r); j <= Math.min(rows, cj + r); j++)
          for (let i = Math.max(0, ci - r); i <= Math.min(cols, ci + r); i++) {
            const dx = i * CELL - hand.x;
            const dy = j * CELL - hand.y;
            const fall = Math.exp(-(dx * dx + dy * dy) / (HAND * HAND * 0.5));
            // Ahead of the hand the ground rises, behind it it sinks: a sweep, not a poke.
            const along = (dx * ux + dy * uy) / HAND;
            speed[j * w + i]! += 26 * strength * fall * (0.35 + along) * dt;
          }
      }
      for (let j = 0; j <= rows; j++)
        for (let i = 0; i <= cols; i++) {
          const k = j * w + i;
          const here = lift[k]!;
          const around =
            ((i > 0 ? lift[k - 1]! : here) +
              (i < cols ? lift[k + 1]! : here) +
              (j > 0 ? lift[k - w]! : here) +
              (j < rows ? lift[k + w]! : here)) /
              4 -
            here;
          let s = speed[k]!;
          s += (COUPLING * around - SPRING * here - FRICTION * s) * dt;
          speed[k] = s;
          spare[k] = here + s * dt;
        }
      const swap = lift;
      lift = spare;
      spare = swap;
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
      const fx = 0.0032 / spread;
      const fy = 0.0041 / spread;
      const swell = 0.18 * relief;
      for (let j = 0; j <= rows; j++) {
        const y = j * CELL;
        const across = Math.cos(y * fy - clock * 0.08);
        for (let i = 0; i <= cols; i++) {
          const x = i * CELL;
          let v = 0;
          for (const c of centres) {
            const dx = x - c.x;
            const dy = y - c.y;
            v += c.h * Math.exp(-(dx * dx + dy * dy) / c.s2);
          }
          v += swell * Math.sin(x * fx + clock * 0.11 + swellPhase) * across;
          for (const w of waves) {
            const d = Math.hypot(x - w.x, y - w.y) - w.radius;
            v += 0.32 * w.strength * w.fade * Math.exp(-(d * d) / 5200);
          }
          const k = j * (cols + 1) + i;
          values[k] = v + lift[k]!;
        }
      }
    };

    // Where a level crosses a grid edge. Even ids are horizontal edges, odd ids vertical.
    const W = () => cols + 1;
    let level = 0;
    const pointX = (edge: number) => {
      const k = edge >> 1;
      const i = k % W();
      if (edge & 1) return i * CELL;
      const a = values[k]!;
      const b = values[k + 1]!;
      return (i + (level - a) / (b - a || 1e-6)) * CELL;
    };
    const pointY = (edge: number) => {
      const k = edge >> 1;
      const j = (k / W()) | 0;
      if (!(edge & 1)) return j * CELL;
      const a = values[k]!;
      const b = values[k + W()]!;
      return (j + (level - a) / (b - a || 1e-6)) * CELL;
    };

    let count = 0;
    const attach = (from: number, to: number) => {
      if (stamp[from] !== visit) {
        stamp[from] = visit;
        near1[from] = to;
        near2[from] = -1;
        touched[count++] = from;
      } else near2[from] = to;
    };
    const link = (p: number, q: number) => {
      attach(p, q);
      attach(q, p);
    };

    /** Traces one level: segments are chained into lines and drawn as smooth curves. */
    const traceLevel = () => {
      const w = W();
      visit += 2; // stamp = visit marks "linked", visit + 1 marks "drawn"
      count = 0;
      for (let j = 0; j < rows; j++) {
        for (let i = 0; i < cols; i++) {
          const k = j * w + i;
          const a = values[k]!;
          const b = values[k + 1]!;
          const c = values[k + w + 1]!;
          const e = values[k + w]!;
          const index =
            (a > level ? 8 : 0) |
            (b > level ? 4 : 0) |
            (c > level ? 2 : 0) |
            (e > level ? 1 : 0);
          if (index === 0 || index === 15) continue;
          const top = k * 2;
          const bottom = (k + w) * 2;
          const left = k * 2 + 1;
          const right = (k + 1) * 2 + 1;
          switch (index) {
            case 1:
            case 14:
              link(left, bottom);
              break;
            case 2:
            case 13:
              link(bottom, right);
              break;
            case 3:
            case 12:
              link(left, right);
              break;
            case 4:
            case 11:
              link(top, right);
              break;
            case 5:
              link(left, top);
              link(bottom, right);
              break;
            case 6:
            case 9:
              link(top, bottom);
              break;
            case 7:
            case 8:
              link(left, top);
              break;
            case 10:
              link(left, bottom);
              link(top, right);
              break;
          }
        }
      }
      const drawn = visit + 1;
      const walk = (start: number) => {
        let length = 0;
        let previous = -1;
        let current = start;
        let closed = false;
        while (current !== -1) {
          chain[length++] = current;
          stamp[current] = drawn;
          const a = near1[current]!;
          const b = near2[current]!;
          const next = a !== previous ? a : b;
          previous = current;
          if (next === -1) break;
          if (stamp[next] === drawn) {
            closed = next === start && length > 2;
            break;
          }
          current = next;
        }
        if (length < 2) return;
        const x = (n: number) => pointX(chain[n]!);
        const y = (n: number) => pointY(chain[n]!);
        if (closed) {
          let px = x(0);
          let py = y(0);
          let qx = x(1);
          let qy = y(1);
          ctx.moveTo((px + qx) / 2, (py + qy) / 2);
          for (let n = 1; n <= length; n++) {
            px = qx;
            py = qy;
            const m = (n + 1) % length;
            qx = x(m);
            qy = y(m);
            ctx.quadraticCurveTo(px, py, (px + qx) / 2, (py + qy) / 2);
          }
          ctx.closePath();
          return;
        }
        ctx.moveTo(x(0), y(0));
        for (let n = 1; n < length - 1; n++) {
          const px = x(n);
          const py = y(n);
          ctx.quadraticCurveTo(
            px,
            py,
            (px + x(n + 1)) / 2,
            (py + y(n + 1)) / 2,
          );
        }
        ctx.lineTo(x(length - 1), y(length - 1));
      };
      // Open lines start at an end (a single neighbour); what is left are closed loops.
      for (let n = 0; n < count; n++) {
        const edge = touched[n]!;
        if (stamp[edge] !== drawn && near2[edge] === -1) walk(edge);
      }
      for (let n = 0; n < count; n++) {
        const edge = touched[n]!;
        if (stamp[edge] !== drawn) walk(edge);
      }
    };

    let ink = "";
    let inkAge = 0;
    const draw = () => {
      if (inkAge-- <= 0) {
        ink = getComputedStyle(canvas).color;
        inkAge = 4;
      }
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.clearRect(0, 0, width, height);
      ctx.strokeStyle = ink;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      for (const index of [false, true]) {
        ctx.beginPath();
        for (let l = 0; l < LEVELS; l++) {
          if ((l % 5 === 2) !== index) continue;
          level = -0.75 + (l * 1.9) / (LEVELS - 1);
          traceLevel();
        }
        ctx.globalAlpha = index ? 0.24 : 0.11;
        ctx.lineWidth = index ? 1.5 : 0.9;
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
    };

    let clock = 20;
    if (reduced()) {
      sample(clock, performance.now());
      draw();
      const still = () => {
        resize();
        sample(clock, performance.now());
        draw();
      };
      window.addEventListener("resize", still);
      return () => window.removeEventListener("resize", still);
    }
    let frame = 0;
    let last = performance.now();
    let skip = false;
    let cost = 0;
    let gustAt = performance.now() + between(4000, 9000);
    const loop = (now: number) => {
      frame = requestAnimationFrame(loop);
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
      // Fixed small steps keep the membrane stable whatever the frame rate.
      let left = dt;
      while (left > 0) {
        const step = Math.min(left, 1 / 60);
        stir(step, now);
        left -= step;
      }
      // Draw every frame; only if drawing gets expensive fall back to every other one.
      if (skip && cost > 7) {
        skip = false;
        return;
      }
      skip = true;
      const begin = performance.now();
      sample(clock, now);
      draw();
      cost = cost * 0.9 + (performance.now() - begin) * 0.1;
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
    window.addEventListener("pointermove", onMove, { passive: true });
    document.documentElement.addEventListener("pointerleave", onLeave);
    start();
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("visibilitychange", visibility);
      window.removeEventListener("resize", resize);
      window.removeEventListener("pointermove", onMove);
      document.documentElement.removeEventListener("pointerleave", onLeave);
    };
  }, []);

  return <canvas ref={ref} className="contours" aria-hidden="true" />;
}
