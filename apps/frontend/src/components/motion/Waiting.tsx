import { useEffect, useState } from "react";
import type { ProductState } from "../../api/types.ts";
import { genreFact, randomGenreIndex } from "../../content/genreNotes.ts";
import { useCopy } from "../../i18n/index.ts";
import { Drum } from "./Drum.tsx";

/** How long each genre stays on screen while waiting. */
const FACT_MS = 6000;

/**
 * Between the request and the first album. The title says honestly whether the request is
 * queued or being worked on; underneath, a random genre from the catalogue and its
 * one-line introduction change every few seconds, something to read while it works.
 */
export function Waiting({
  state,
  settling,
  onSettled,
}: {
  state: ProductState | null;
  settling: boolean;
  onSettled: () => void;
}) {
  const words = useCopy().waiting;
  const [index, setIndex] = useState(() => randomGenreIndex());
  useEffect(() => {
    if (settling) return;
    const timer = setInterval(
      () => setIndex((previous) => randomGenreIndex(previous)),
      FACT_MS,
    );
    return () => clearInterval(timer);
  }, [settling]);
  const queued = state?.status === "WAITING";
  const title = settling
    ? words.settling
    : queued
      ? words.queued
      : words.working;
  const fact = genreFact(index);
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
        {settling ? (
          <p className="waiting__detail mono">{words.soon}</p>
        ) : (
          // Not announced: a new genre every few seconds would talk over everything.
          <div className="waiting__fact" key={index} aria-live="off">
            <p className="waiting__genre mono">{fact.name}</p>
            <p className="waiting__note">{fact.text}</p>
          </div>
        )}
      </div>
    </section>
  );
}
