import { useEffect, useState } from "react";
import type { ProductState } from "../../api/types.ts";
import { Drum } from "./Drum.tsx";

const clock = (ms: number) => {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
};

/**
 * Between the request and the first album. Only honest progress is shown: whether the
 * request is queued or being worked on, and how long this page has been waiting.
 */
export function Waiting({
  state,
  since,
  settling,
  onSettled,
}: {
  state: ProductState | null;
  since: number;
  settling: boolean;
  onSettled: () => void;
}) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (settling) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [settling]);
  const queued = state?.status === "WAITING";
  const title = settling ? "挑好了" : queued ? "排队中" : "正在挑选";
  const detail = settling
    ? "马上就来"
    : queued
      ? `轮到你时会自动开始 · ${clock(now - since)}`
      : `已等待 ${clock(now - since)}`;
  return (
    <section className="waiting" aria-live="polite" aria-label="正在推荐">
      <Drum settling={settling} onSettled={onSettled} />
      <div className="waiting__text">
        <p className="waiting__title" key={title}>
          {[...title].map((char, i) => (
            <span key={i} style={{ animationDelay: `${i * 45}ms` }}>
              {char}
            </span>
          ))}
        </p>
        <p className="waiting__detail mono">{detail}</p>
      </div>
    </section>
  );
}
