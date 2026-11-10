import { useEffect, useRef } from "react";

/**
 * Shared "energy" of the background strings. Anything can pluck them: switching albums,
 * a reaction, the end of the wait. The vibration decays on its own.
 */
const field = { energy: 0.6, calm: 0.35 };

export function pluck(amount = 1) {
  field.energy = Math.min(2.4, field.energy + amount);
}

/** Resting amplitude the strings settle to (busy while waiting, quiet while reading). */
export function setCalm(level: number) {
  field.calm = level;
}

const W = 1000;
const H = 1000;
const COUNT = 9;
const POINTS = 48;

const reduced = () =>
  typeof window !== "undefined" &&
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/**
 * Background of the whole app: a set of strings fixed at both edges of the screen. Each is
 * a sum of two standing waves (so it bends like a real string, never a scrolling sine)
 * plus a slow drift; plucks add amplitude that dies away. Drawn with plain SVG paths in a
 * single requestAnimationFrame loop that pauses while the tab is hidden.
 */
export function Strings() {
  const ref = useRef<SVGSVGElement>(null);
  useEffect(() => {
    const svg = ref.current;
    if (!svg) return;
    const paths = [...svg.querySelectorAll("path")];
    const seeds = paths.map((_, i) => ({
      base: 120 + (i * (H - 240)) / (COUNT - 1),
      tilt: ((i % 3) - 1) * 26,
      modeA: 1 + (i % 3),
      modeB: 2 + ((i * 2) % 5),
      speedA: 0.55 + (i % 4) * 0.13,
      speedB: 0.9 + (i % 5) * 0.11,
      phase: i * 1.7,
      weight: 0.55 + ((i * 37) % 10) / 22,
    }));
    const draw = (t: number, amp: number) => {
      paths.forEach((path, i) => {
        const s = seeds[i]!;
        let d = "";
        for (let p = 0; p <= POINTS; p++) {
          const u = p / POINTS;
          const x = u * W;
          const pin = Math.sin(Math.PI * u); // fixed at both ends
          const y =
            s.base +
            s.tilt * (u - 0.5) +
            amp *
              s.weight *
              pin *
              (Math.sin(Math.PI * s.modeA * u) *
                Math.cos(t * s.speedA + s.phase) *
                38 +
                Math.sin(Math.PI * s.modeB * u) *
                  Math.cos(t * s.speedB + s.phase * 0.5) *
                  14);
          d += `${p ? "L" : "M"}${x.toFixed(1)} ${y.toFixed(1)}`;
        }
        path.setAttribute("d", d);
      });
    };
    if (reduced()) {
      draw(0, 0.4);
      return;
    }
    let frame = 0;
    let last = performance.now();
    let clock = 0;
    const loop = (now: number) => {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      clock += dt;
      // Exponential decay toward the resting level.
      field.energy += (field.calm - field.energy) * (1 - Math.exp(-dt * 1.6));
      draw(clock, field.energy);
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
    start();
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("visibilitychange", visibility);
    };
  }, []);
  return (
    <svg
      ref={ref}
      className="strings"
      viewBox={`0 0 ${W} ${H}`}
      preserveAspectRatio="none"
      aria-hidden="true"
      focusable="false"
    >
      {Array.from({ length: COUNT }, (_, i) => (
        <path
          key={i}
          className="strings__line"
          style={{ opacity: 0.06 + (i % 4) * 0.035 }}
          vectorEffect="non-scaling-stroke"
        />
      ))}
    </svg>
  );
}
