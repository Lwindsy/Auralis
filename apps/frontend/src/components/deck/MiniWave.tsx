import type { CSSProperties } from "react";

/**
 * A handful of meter bars: a tiny "something is playing" mark used for writing, supply
 * and the end-of-group choices. `live` makes the bars bounce; `calm` keeps them low.
 */
export function MiniWave({
  bars = 9,
  live,
  calm,
  className,
}: {
  bars?: number;
  live?: boolean;
  calm?: boolean;
  className?: string;
}) {
  return (
    <svg
      className={["mini-wave", className ?? ""].join(" ")}
      data-live={live || undefined}
      data-calm={calm || undefined}
      viewBox={`0 0 ${bars * 6} 24`}
      aria-hidden="true"
      focusable="false"
    >
      {Array.from({ length: bars }, (_, i) => {
        const h = 0.35 + 0.65 * Math.abs(Math.sin(i * 1.7 + 0.6));
        return (
          <line
            key={i}
            x1={i * 6 + 3}
            x2={i * 6 + 3}
            y1={12 - h * 10}
            y2={12 + h * 10}
            style={{ "--i": i, "--h": h.toFixed(2) } as CSSProperties}
          />
        );
      })}
    </svg>
  );
}
