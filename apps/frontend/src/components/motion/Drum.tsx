import { useEffect, useRef } from "react";

const TAU = Math.PI * 2;
const SETTLE_MS = 2000;
const LOOP_POINTS = 120;

const reduced = () =>
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;

function loopPath(radius: number, wobble: number, phase: number) {
  let d = "";
  for (let i = 0; i <= LOOP_POINTS; i++) {
    const a = (i / LOOP_POINTS) * TAU;
    const r =
      radius +
      wobble *
        (Math.sin(3 * a + phase) * 9 +
          Math.sin(5 * a - phase * 1.3) * 5 +
          Math.sin(2 * a + phase * 0.7) * 4);
    d += `${i ? "L" : "M"}${(Math.cos(a) * r).toFixed(2)} ${(Math.sin(a) * r).toFixed(2)}`;
  }
  return `${d}Z`;
}

/**
 * The waiting piece: a perforated drum, a dotted ring and an accent ring turning at
 * different speeds around a vibrating loop. While `settling` it does not stop dead: the
 * spin and the vibration run down together, the loop rounds off, gives one soft pulse and
 * only then calls `onSettled`, the way a washing machine winds down at the end of a cycle.
 */
export function Drum({
  settling,
  onSettled,
}: {
  settling: boolean;
  onSettled?: () => void;
}) {
  const ref = useRef<SVGSVGElement>(null);
  const settleAt = useRef<number | null>(null);
  const settledRef = useRef(onSettled);
  useEffect(() => {
    settledRef.current = onSettled;
  });
  useEffect(() => {
    settleAt.current = settling ? performance.now() : null;
    if (settling && reduced()) {
      const timer = window.setTimeout(() => settledRef.current?.(), 300);
      return () => window.clearTimeout(timer);
    }
  }, [settling]);

  useEffect(() => {
    const svg = ref.current;
    if (!svg) return;
    const ticks = svg.querySelector<SVGGElement>(".drum__ticks")!;
    const dots = svg.querySelector<SVGCircleElement>(".drum__dots")!;
    const accent = svg.querySelector<SVGCircleElement>(".drum__accent")!;
    const loop = svg.querySelector<SVGPathElement>(".drum__loop")!;
    const echo = svg.querySelector<SVGPathElement>(".drum__echo")!;
    let frame = 0;
    let last = performance.now();
    let done = false;
    const angle = [0, 0, 0];
    let phase = 0;
    const speeds = [0.22, -0.7, 1.35];
    const step = (now: number) => {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      let factor = 1;
      if (settleAt.current !== null) {
        const p = Math.min(1, (now - settleAt.current) / SETTLE_MS);
        factor = (1 - p) * (1 - p) * (1 - p);
        if (p >= 1 && !done) {
          done = true;
          svg.dataset.state = "settled";
          window.setTimeout(() => settledRef.current?.(), 650);
        }
      }
      for (let i = 0; i < 3; i++) angle[i]! += speeds[i]! * factor * dt;
      phase += 2.4 * factor * dt;
      ticks.setAttribute("transform", `rotate(${(angle[0]! * 180) / Math.PI})`);
      dots.setAttribute("transform", `rotate(${(angle[1]! * 180) / Math.PI})`);
      accent.setAttribute(
        "transform",
        `rotate(${(angle[2]! * 180) / Math.PI})`,
      );
      loop.setAttribute("d", loopPath(96, factor, phase));
      echo.setAttribute("d", loopPath(112, factor * 0.6, phase + 1.2));
      if (!done || factor > 0) frame = requestAnimationFrame(step);
    };
    if (reduced()) {
      loop.setAttribute("d", loopPath(96, 0, 0));
      echo.setAttribute("d", loopPath(112, 0, 0));
      return;
    }
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, []);

  return (
    <svg
      ref={ref}
      className="drum"
      viewBox="-220 -220 440 440"
      aria-hidden="true"
      focusable="false"
      data-state="spinning"
    >
      <g className="drum__ticks">
        {Array.from({ length: 120 }, (_, i) => {
          const a = (i / 120) * TAU;
          const inner = i % 10 === 0 ? 188 : 196;
          return (
            <line
              key={i}
              x1={Math.cos(a) * inner}
              y1={Math.sin(a) * inner}
              x2={Math.cos(a) * 206}
              y2={Math.sin(a) * 206}
            />
          );
        })}
      </g>
      <circle className="drum__dots" r="170" pathLength="360" />
      <circle className="drum__accent" r="142" pathLength="360" />
      <path className="drum__echo" />
      <path className="drum__loop" />
      <circle className="drum__core" r="34" />
      <circle className="drum__spindle" r="4" />
    </svg>
  );
}
