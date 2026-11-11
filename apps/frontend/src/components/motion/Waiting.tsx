import { useEffect, useState } from "react";
import type { ProductState } from "../../api/types.ts";
import { useCopy } from "../../i18n/index.ts";
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
  const words = useCopy().waiting;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (settling) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [settling]);
  const queued = state?.status === "WAITING";
  const title = settling
    ? words.settling
    : queued
      ? words.queued
      : words.working;
  const detail = settling
    ? words.soon
    : queued
      ? words.queuedFor(clock(now - since))
      : words.waited(clock(now - since));
  return (
    <section className="waiting" aria-live="polite" aria-label={words.label}>
      <Drum settling={settling} onSettled={onSettled} />
      <div className="waiting__text">
        <p className="waiting__title" key={title}>
          {/* Each letter rises on its own; spaces are kept as fixed gaps between them. */}
          {[...title].map((char, i) => (
            <span key={i} style={{ animationDelay: `${i * 45}ms` }}>
              {char === " " ? "\u00a0" : char}
            </span>
          ))}
        </p>
        <p className="waiting__detail mono">{detail}</p>
      </div>
    </section>
  );
}
