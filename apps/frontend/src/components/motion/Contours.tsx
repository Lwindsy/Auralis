import { useEffect, useRef } from "react";

/**
 * A topographic map of sound, drawn on one canvas every frame, in three layers:
 *
 * - Relief shading: the same terrain lit from one side (a hillshade), so ridges have a
 *   lit flank and a shadowed one. The lamp drifts slowly toward the pointer.
 * - Contour lines: the terrain traced at even heights with marching squares; crossings
 *   are chained into whole lines and drawn as quadratic curves through their midpoints.
 *   Every fifth line is an index contour, drawn heavier, as on a survey map. The heights
 *   are exposed automatically from what is on screen (like a camera's exposure), so there
 *   are about as many lines in every mode and on every page.
 * - Drift: a few hundred grains of light carried over the land, each with a short trail.
 *
 * The exploration mode gives the land a temperament rather than a shape:
 * 熟悉 breathes in place — the hills swell and settle slowly and barely wander, and the
 * grains circle along the contours; 拓展 flows — the whole land travels steadily one way
 * like a current and the grains run with it; 惊喜 erupts — new hills keep rising out of
 * nowhere and sinking again, gusts come more often, and the grains scatter downhill.
 * Switching modes blends one temperament into the next over a few seconds.
 *
 * The pointer is a hand passing through all of it. Under the map lies an elastic membrane
 * (a damped spring at every grid point, coupled to its neighbours): a fast sweep pushes
 * the ground up ahead of the hand and down behind it and sweeps the grains aside; the
 * springs keep pulling everything back to rest. A slow hand barely stirs it.
 *
 * The terrain is never the same twice: hills are seeded at random on each load, their
 * pace wanders, and now and then a stray gust drops a faint ripple somewhere.
 */

interface Ripple {
  x: number;
  y: number;
  start: number;
  strength: number;
}

export type Terrain = "familiar" | "explore" | "surprise";
type Mix = Record<Terrain, number>;

const ONE_HOT = (mode: Terrain): Mix => ({
  familiar: mode === "familiar" ? 1 : 0,
  explore: mode === "explore" ? 1 : 0,
  surprise: mode === "surprise" ? 1 : 0,
});

const field = {
  flow: 0.5,
  target: 0.5,
  ripples: [] as Ripple[],
  mix: ONE_HOT("explore"),
  goal: ONE_HOT("explore"),
};

/** Give the land the temperament of an exploration mode; it blends in over a few seconds. */
export function setTerrain(mode: Terrain) {
  field.goal = ONE_HOT(mode);
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

const LEVELS = 16;
const CELL = 26;

/* Membrane: spring back to rest, coupling to neighbours, friction. */
const SPRING = 7;
const COUPLING = 16;
const FRICTION = 2.6;
/** Hand radius in px and the speed (px/s) at which it disturbs at full strength. */
const HAND = 150;
const FULL_SPEED = 1600;

/** Hillshade: height exaggeration and the lamp's elevation. */
const RELIEF = 150;
const ELEVATION = 0.8;

/** Hills of the travelling land wrap around this band (in screen fractions). */
const BAND_FROM = -0.4;
const BAND = 1.8;

const reduced = () =>
  typeof window !== "undefined" &&
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

interface Hill {
  bx: number;
  by: number;
  ax: number;
  ay: number;
  fx: number;
  fy: number;
  phase: number;
  size: number;
  height: number;
}

/** A hill of 惊喜 that rises and sinks again; times are on the terrain clock. */
interface Eruption {
  x: number;
  y: number;
  size: number;
  height: number;
  born: number;
  life: number;
}

interface Grain {
  x: number;
  y: number;
  vx: number;
  vy: number;
  age: number;
  life: number;
  /** Each grain keeps its own pace, so a stream never looks ruled. */
  pace: number;
}

const between = (low: number, high: number) =>
  low + Math.random() * (high - low);
const smooth = (t: number) => {
  const x = Math.max(0, Math.min(1, t));
  return x * x * (3 - 2 * x);
};

/** A fresh landscape for every visit. */
function seedHills(): Hill[] {
  const count = 6 + Math.floor(Math.random() * 3);
  return Array.from({ length: count }, (_, i) => ({
    bx: between(0.08, 0.92),
    by: between(0.1, 0.9),
    ax: between(0.06, 0.16),
    ay: between(0.05, 0.14),
    fx: between(0.05, 0.12),
    fy: between(0.04, 0.11),
    phase: between(0, Math.PI * 2),
    size: between(0.16, 0.36),
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
    const shadeCanvas = document.createElement("canvas");
    const shadeContext = shadeCanvas.getContext("2d");
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
    let shadeImage: ImageData | null = null;
    let grains: Grain[] = [];
    // The centre fade, drawn into the canvas rather than applied as a CSS mask: a mask on
    // a full-screen layer that changes every frame costs the compositor a pass per frame.
    let centreFade: CanvasGradient | null = null;
    let fadeSquash = 1;
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
      shadeCanvas.width = cols + 1;
      shadeCanvas.height = rows + 1;
      shadeImage = shadeContext?.createImageData(cols + 1, rows + 1) ?? null;
      const count = Math.round(
        Math.max(110, Math.min(300, (width * height) / 5200)),
      );
      grains = Array.from({ length: count }, () => spawn(true));
      // Same shape as the old mask: an ellipse 120% × 90% of the screen, 45% strength at
      // the centre rising to full at three quarters of the way out.
      const rx = width * 1.2;
      fadeSquash = (height * 0.9) / rx;
      const cy = height / 2 / fadeSquash;
      centreFade = ctx.createRadialGradient(
        width / 2,
        cy,
        0,
        width / 2,
        cy,
        rx,
      );
      centreFade.addColorStop(0, "rgba(0,0,0,0.45)");
      centreFade.addColorStop(0.75, "#000");
    };
    const spawn = (anywhere: boolean): Grain => ({
      x: between(0, width),
      y: between(0, height),
      vx: 0,
      vy: 0,
      age: anywhere ? between(0, 6) : 0,
      life: between(5, 11),
      pace: between(0.55, 1.35),
    });

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
    // 拓展's current: one heading per visit, mostly sideways.
    const heading = between(-0.45, 0.45) + (Math.random() < 0.5 ? 0 : Math.PI);
    const current = { x: Math.cos(heading), y: Math.sin(heading) };
    let travel = between(0, 1);
    let eruptions: Eruption[] = [];
    let eruptionDebt = 0;

    const sample = (clock: number, now: number) => {
      const scale = Math.max(width, height);
      const { familiar, explore, surprise } = field.mix;
      const wrap = (u: number) =>
        ((((u - BAND_FROM) % BAND) + BAND) % BAND) + BAND_FROM;
      const centres = hills.map((h) => {
        // 熟悉 holds the hills almost still; 拓展 carries them along the current and
        // wraps them round, fading them out and in beyond the edges.
        const sway = 1 - 0.6 * familiar;
        const u = wrap(
          h.bx +
            current.x * travel +
            h.ax * sway * Math.sin(clock * h.fx + h.phase),
        );
        const v = wrap(
          h.by +
            current.y * travel +
            h.ay * sway * Math.cos(clock * h.fy + h.phase * 1.3),
        );
        const edge = Math.min(
          u - BAND_FROM,
          BAND_FROM + BAND - u,
          v - BAND_FROM,
          BAND_FROM + BAND - v,
        );
        const breath =
          1 + 0.38 * familiar * Math.sin(clock * 0.85 + h.phase * 0.35);
        return {
          x: width * u,
          y: height * v,
          s2: (h.size * scale) ** 2,
          h: h.height * breath * smooth(edge / 0.32),
        };
      });
      // 惊喜: hills rise quickly out of nowhere and sink back more slowly.
      for (const e of eruptions) {
        const age = (clock - e.born) / e.life;
        const envelope =
          age < 0.25
            ? 1 - (1 - age / 0.25) ** 3
            : 1 - smooth((age - 0.25) / 0.75);
        centres.push({
          x: e.x * width,
          y: e.y * height,
          s2: (e.size * scale) ** 2,
          h: e.height * envelope * (0.25 + 0.75 * surprise),
        });
      }
      const waves = field.ripples.map((r) => ({
        ...r,
        radius: ((now - r.start) / 1000) * 520,
        fade: Math.exp(-(now - r.start) / 1400),
      }));
      const fx = 0.0032;
      const fy = 0.0041;
      // The swell runs with the current in 拓展.
      const slide = clock * (0.11 + 0.25 * explore);
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
          v += 0.18 * Math.sin(x * fx - slide + swellPhase) * across;
          for (const w of waves) {
            const d = Math.hypot(x - w.x, y - w.y) - w.radius;
            v += 0.32 * w.strength * w.fade * Math.exp(-(d * d) / 5200);
          }
          const k = j * (cols + 1) + i;
          values[k] = v + lift[k]!;
        }
      }
    };

    // Exposure: the contour heights span what is actually on screen (3rd–97th
    // percentile of the field), eased so the lines never jump.
    const exposure = { lo: 0, hi: 0, ready: false };
    const histogram = new Uint32Array(64);
    const expose = (dt: number) => {
      let min = Infinity;
      let max = -Infinity;
      for (let k = 0; k < values.length; k++) {
        const v = values[k]!;
        if (v < min) min = v;
        if (v > max) max = v;
      }
      const span = max - min || 1e-6;
      histogram.fill(0);
      for (let k = 0; k < values.length; k++)
        histogram[
          Math.min(63, Math.floor(((values[k]! - min) / span) * 64))
        ]!++;
      const quantile = (q: number) => {
        let seen = 0;
        const goal = q * values.length;
        for (let b = 0; b < 64; b++) {
          seen += histogram[b]!;
          if (seen >= goal) return min + ((b + 0.5) / 64) * span;
        }
        return max;
      };
      let lo = quantile(0.03);
      let hi = quantile(0.97);
      // Keep a least range so a nearly flat field does not turn into noise.
      const least = 0.35;
      if (hi - lo < least) {
        const mid = (hi + lo) / 2;
        lo = mid - least / 2;
        hi = mid + least / 2;
      }
      if (!exposure.ready) {
        exposure.lo = lo;
        exposure.hi = hi;
        exposure.ready = true;
        return;
      }
      const ease = 1 - Math.exp(-dt / 1.4);
      exposure.lo += (lo - exposure.lo) * ease;
      exposure.hi += (hi - exposure.hi) * ease;
    };

    // Where a level crosses a grid edge. Even ids are horizontal edges, odd ids vertical.
    let level = 0;
    const pointX = (edge: number) => {
      const w = cols + 1;
      const k = edge >> 1;
      const i = k % w;
      if (edge & 1) return i * CELL;
      const a = values[k]!;
      const b = values[k + 1]!;
      return (i + (level - a) / (b - a || 1e-6)) * CELL;
    };
    const pointY = (edge: number) => {
      const w = cols + 1;
      const k = edge >> 1;
      const j = (k / w) | 0;
      if (!(edge & 1)) return j * CELL;
      const a = values[k]!;
      const b = values[k + w]!;
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
      const w = cols + 1;
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
        let at = start;
        let closed = false;
        while (at !== -1) {
          chain[length++] = at;
          stamp[at] = drawn;
          const a = near1[at]!;
          const b = near2[at]!;
          const next = a !== previous ? a : b;
          previous = at;
          if (next === -1) break;
          if (stamp[next] === drawn) {
            closed = next === start && length > 2;
            break;
          }
          at = next;
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

    /** Field value at any point (bilinear between grid nodes). */
    const valueAt = (x: number, y: number) => {
      const gx = Math.max(0, Math.min(cols - 1e-3, x / CELL));
      const gy = Math.max(0, Math.min(rows - 1e-3, y / CELL));
      const i = gx | 0;
      const j = gy | 0;
      const tx = gx - i;
      const ty = gy - j;
      const w = cols + 1;
      const k = j * w + i;
      const top = values[k]! + (values[k + 1]! - values[k]!) * tx;
      const bottom =
        values[k + w]! + (values[k + w + 1]! - values[k + w]!) * tx;
      return top + (bottom - top) * ty;
    };

    // The lamp for the relief: from the upper left at rest, drifting toward the pointer.
    const lamp = { angle: (-3 * Math.PI) / 4 };
    const shade = (dt: number) => {
      if (!shadeImage || !shadeContext) return;
      if (hand.active) {
        const goal = Math.atan2(hand.y - height / 2, hand.x - width / 2);
        let turn = goal - lamp.angle;
        turn = Math.atan2(Math.sin(turn), Math.cos(turn));
        lamp.angle += turn * (1 - Math.exp(-dt / 1.6));
      }
      const lx = Math.cos(lamp.angle) * Math.cos(ELEVATION);
      const ly = Math.sin(lamp.angle) * Math.cos(ELEVATION);
      const lz = Math.sin(ELEVATION);
      const w = cols + 1;
      const data = shadeImage.data;
      const range = Math.max(0.35, exposure.hi - exposure.lo);
      const gain = RELIEF / range;
      // A dark room needs a brighter lit side; a light room a deeper shadow.
      const glow = darkRoom ? 58 : 46;
      const gloom = darkRoom ? 120 : 64;
      for (let j = 0; j <= rows; j++)
        for (let i = 0; i <= cols; i++) {
          const k = j * w + i;
          const left = values[i > 0 ? k - 1 : k]!;
          const right = values[i < cols ? k + 1 : k]!;
          const up = values[j > 0 ? k - w : k]!;
          const down = values[j < rows ? k + w : k]!;
          const nx = (-(right - left) / (2 * CELL)) * gain;
          const ny = (-(down - up) / (2 * CELL)) * gain;
          const length = Math.hypot(nx, ny, 1);
          // Lit minus flat: positive faces the lamp, negative turns away from it.
          const light = (nx * lx + ny * ly + lz) / length - lz;
          const p = k * 4;
          if (light >= 0) {
            data[p] = data[p + 1] = data[p + 2] = 255;
            data[p + 3] = Math.min(1, light * 2.2) * glow;
          } else {
            data[p] = data[p + 1] = data[p + 2] = 0;
            data[p + 3] = Math.min(1, -light * 2.2) * gloom;
          }
        }
      shadeContext.putImageData(shadeImage, 0, 0);
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = "high";
      ctx.drawImage(
        shadeCanvas,
        -CELL / 2,
        -CELL / 2,
        (cols + 1) * CELL,
        (rows + 1) * CELL,
      );
    };

    /** The grains: circling (熟悉), running with the current (拓展), scattering (惊喜). */
    const drift = (dt: number, clock: number) => {
      const { familiar, explore, surprise } = field.mix;
      const pace = 24 * (0.45 + 0.75 * field.flow);
      const step = CELL * 0.75;
      const handSpeed = Math.hypot(hand.vx, hand.vy);
      for (let n = 0; n < grains.length; n++) {
        let g = grains[n]!;
        g.age += dt;
        if (
          g.age > g.life ||
          g.x < -20 ||
          g.y < -20 ||
          g.x > width + 20 ||
          g.y > height + 20
        ) {
          g = grains[n] = spawn(false);
        }
        const gx = (valueAt(g.x + step, g.y) - valueAt(g.x - step, g.y)) / 2;
        const gy = (valueAt(g.x, g.y + step) - valueAt(g.x, g.y - step)) / 2;
        const slope = Math.hypot(gx, gy) || 1e-6;
        const tx = -gy / slope;
        const ty = gx / slope;
        const steep = Math.min(1, slope * 8);
        // 拓展's current meanders a little across the screen.
        const bend = 0.4 * Math.sin(g.y * 0.005 + g.x * 0.002 + clock * 0.35);
        const cx = current.x * Math.cos(bend) - current.y * Math.sin(bend);
        const cy = current.x * Math.sin(bend) + current.y * Math.cos(bend);
        let vx =
          familiar * tx * (0.4 + steep) +
          explore * (cx * 1.05 + tx * 0.35 * steep) +
          surprise * (-gx / slope) * (0.3 + steep);
        let vy =
          familiar * ty * (0.4 + steep) +
          explore * (cy * 1.05 + ty * 0.35 * steep) +
          surprise * (-gy / slope) * (0.3 + steep);
        vx *= pace * g.pace;
        vy *= pace * g.pace;
        if (hand.active && handSpeed > 40) {
          const dx = g.x - hand.x;
          const dy = g.y - hand.y;
          const fall = Math.exp(-(dx * dx + dy * dy) / (HAND * HAND * 0.6));
          vx += hand.vx * fall * 0.35;
          vy += hand.vy * fall * 0.35;
        }
        g.vx += (vx - g.vx) * (1 - Math.exp(-dt * 3));
        g.vy += (vy - g.vy) * (1 - Math.exp(-dt * 3));
        g.x += g.vx * dt;
        g.y += g.vy * dt;
      }
    };
    /** Each grain is a bright point with a faint trail behind it, like a slow comet. */
    // Reused every frame instead of filtering three fresh arrays.
    const buckets: Grain[][] = [[], [], []];
    const BANDS = [0.25, 0.55, 0.9] as const;
    const drawGrains = () => {
      for (const bucket of buckets) bucket.length = 0;
      for (const g of grains) {
        const fade = Math.min(g.age / 1.2, (g.life - g.age) / 1.6, 1);
        if (fade <= 0) continue;
        buckets[fade < 0.4 ? 0 : fade < 0.75 ? 1 : 2]!.push(g);
      }
      for (let b = 0; b < 3; b++) {
        const band = BANDS[b]!;
        const members = buckets[b]!;
        ctx.beginPath();
        for (const g of members) {
          ctx.moveTo(g.x, g.y);
          ctx.lineTo(g.x - g.vx * 0.55, g.y - g.vy * 0.55);
        }
        ctx.lineWidth = 0.8;
        ctx.globalAlpha = 0.16 * band;
        ctx.stroke();
        ctx.beginPath();
        for (const g of members) {
          ctx.moveTo(g.x, g.y);
          ctx.lineTo(g.x - g.vx * 0.04, g.y - g.vy * 0.04);
        }
        ctx.lineWidth = 1.7;
        ctx.globalAlpha = 0.5 * band;
        ctx.stroke();
      }
    };

    let ink = "";
    let inkAge = 0;
    let darkRoom = true;
    const draw = (dt: number, motion: boolean) => {
      if (inkAge-- <= 0) {
        ink = getComputedStyle(canvas).color;
        inkAge = 4;
        // Light ink means a dark room.
        const channels = (ink.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number);
        // rgb() gives 0–255; color(srgb …) gives 0–1.
        const unit = Math.max(...channels, 0) <= 1 ? 255 : 1;
        const [r = 1, g = 1, b = 1] = channels.map((c) => c * unit);
        darkRoom = 0.2126 * r + 0.7152 * g + 0.0722 * b > 128;
      }
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.clearRect(0, 0, width, height);
      ctx.globalAlpha = 1;
      shade(dt);
      ctx.strokeStyle = ink;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      const span = exposure.hi - exposure.lo;
      for (const index of [false, true]) {
        ctx.beginPath();
        for (let l = 0; l < LEVELS; l++) {
          if ((l % 5 === 2) !== index) continue;
          level = exposure.lo + (span * (l + 0.5)) / LEVELS;
          traceLevel();
        }
        ctx.globalAlpha = index ? 0.24 : 0.11;
        ctx.lineWidth = index ? 1.5 : 0.9;
        ctx.stroke();
      }
      if (motion) drawGrains();
      if (centreFade) {
        ctx.globalAlpha = 1;
        ctx.globalCompositeOperation = "destination-in";
        ctx.setTransform(ratio, 0, 0, ratio * fadeSquash, 0, 0);
        ctx.fillStyle = centreFade;
        ctx.fillRect(0, 0, width, height / fadeSquash);
        ctx.globalCompositeOperation = "source-over";
      }
      ctx.globalAlpha = 1;
    };

    let clock = 20;
    if (reduced()) {
      const still = () => {
        resize();
        sample(clock, performance.now());
        expose(1);
        draw(1, false);
      };
      still();
      window.addEventListener("resize", still);
      return () => window.removeEventListener("resize", still);
    }
    let frame = 0;
    let last = performance.now();
    let skip = false;
    let cost = 0;
    let drawnAt = performance.now();
    let gustAt = performance.now() + between(4000, 9000);
    const loop = (now: number) => {
      frame = requestAnimationFrame(loop);
      const dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      field.flow += (field.target - field.flow) * (1 - Math.exp(-dt * 1.2));
      const ease = 1 - Math.exp(-dt * 0.7);
      for (const mode of ["familiar", "explore", "surprise"] as const)
        field.mix[mode] += (field.goal[mode] - field.mix[mode]) * ease;
      const { familiar, explore, surprise } = field.mix;
      // The pace itself wanders a little, so the drift never feels mechanical; 熟悉 is
      // the calmest.
      const wander = 1 + 0.35 * Math.sin(now / 7300) * Math.sin(now / 3100);
      const tick = dt * field.flow * wander * (1 - 0.35 * familiar);
      clock += tick;
      travel += tick * explore * 0.05;
      // 惊喜: new hills keep rising; the older ones sink away.
      eruptionDebt += tick * surprise * 0.75;
      while (eruptionDebt >= 1) {
        eruptionDebt -= 1;
        eruptions.push({
          x: between(0.05, 0.95),
          y: between(0.08, 0.92),
          size: between(0.06, 0.15),
          height: (Math.random() < 0.6 ? 1 : -1) * between(0.55, 1.1),
          born: clock,
          life: between(4.5, 8.5),
        });
      }
      eruptions = eruptions.filter((e) => clock - e.born < e.life).slice(-12);
      if (now > gustAt) {
        gustAt = now + between(6000, 14000) * (1 - 0.6 * surprise);
        field.ripples.push({
          x: between(0, width),
          y: between(0, height),
          start: now,
          strength: between(0.2, 0.5) * (1 + 0.6 * surprise),
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
      const since = Math.min(0.1, (now - drawnAt) / 1000);
      drawnAt = now;
      sample(clock, now);
      expose(since);
      drift(since, clock);
      draw(since, true);
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
