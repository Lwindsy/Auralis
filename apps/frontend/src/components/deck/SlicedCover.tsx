import { useEffect, useRef, useState, type CSSProperties } from "react";
import { albumSeed, coverArt, hash } from "../../lib/cover.ts";
import { useCopy } from "../../i18n/index.ts";
import { Cover } from "../Cover.tsx";
import { useCover } from "../../state/covers.ts";

const SLICES = 30;
/** The assembly ends after this long (incoming albums start a little later). */
const ASSEMBLED_MS = 2300;
/** One hover ripple: 900ms per strip plus the stagger across the strips. */
const RIPPLE_MS = 900 + SLICES * 14 + 60;

/**
 * - assembling: the strips bounce and close up; the whole cover fades in over them.
 * - whole: only the whole cover is in the page (the strips are gone, so an idle album
 *   costs nothing to keep on screen).
 * - rippling: the strips come back, already closed up, for one hover ripple.
 * - leaving: the strips break apart and sink; the whole cover is hidden at once.
 */
type Phase = "assembling" | "whole" | "rippling" | "leaving";

/**
 * How a leaving copy goes: back into strips (to another album), whole into the group's
 * stack on the finale (Deck flies it there), or a quiet fade when there is no stack.
 */
export type CoverExit = "slices" | "flight" | "quiet";

/**
 * The sleeve as a waveform. It is cut into thin vertical strips that bounce like the bars
 * of an audio meter, then close up into the whole cover. Leaving, it breaks back into a
 * waveform and sinks. Strip heights come from the album's own hash, so every album has its
 * own "voiceprint".
 *
 * Moving onto the cover sends exactly one small ripple across the strips. The ripple runs
 * on an inner layer and is started from script, so moving off the cover does nothing.
 *
 * Kept light on purpose: every strip is a single painted box (the cover image, or the
 * placeholder field, offset to its own slice) and every keyframe animates only transform
 * and opacity, so the compositor runs the assembly without repainting. The strips exist
 * only while they move.
 */
export function SlicedCover({
  title,
  artist,
  url,
  leaving = false,
}: {
  title: string;
  artist: string;
  url: string | null;
  /** Render the leaving copy of an album that is being replaced, and how it goes. */
  leaving?: CoverExit | false;
}) {
  const words = useCopy();
  const seed = albumSeed(title, artist);
  const image = useCover(url);
  const base = hash(seed);
  const level = (i: number, k: number) => {
    const v = hash(`${base}:${i}:${k}`) / 0xffffffff;
    // A soft envelope keeps the middle louder than the edges, like a real waveform.
    const envelope = 0.45 + 0.55 * Math.sin((Math.PI * (i + 0.5)) / SLICES);
    return (0.12 + 0.88 * v * envelope).toFixed(3);
  };
  const [phase, setPhase] = useState<Phase>(
    leaving === "slices" ? "leaving" : leaving ? "whole" : "assembling",
  );
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => {
    if (leaving) return;
    timer.current = setTimeout(() => setPhase("whole"), ASSEMBLED_MS);
    return () => clearTimeout(timer.current);
  }, [leaving]);
  const ripple = () => {
    if (phase !== "whole" || leaving) return;
    setPhase("rippling");
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setPhase("whole"), RIPPLE_MS);
  };
  // One painted box per strip: the decoded image, or the placeholder field.
  const art = coverArt(seed);
  const paint: CSSProperties = image
    ? {
        backgroundImage: `url("${image.url}")`,
        backgroundSize: "cover",
        backgroundPosition: "center",
      }
    : { background: art.background };
  return (
    <div
      className="sliced"
      role="img"
      aria-label={words.cover.alt(title)}
      data-phase={phase}
      data-exit={leaving && leaving !== "slices" ? leaving : undefined}
      data-rippling={phase === "rippling" || undefined}
      onPointerEnter={(event) => {
        if (event.pointerType !== "touch") ripple();
      }}
    >
      {/* The whole cover's shadow; moving strips carry their own slices of it. */}
      <div className="sliced__shadow" aria-hidden="true" />
      {/* The seamless cover settles over the strips once they have closed up. */}
      <div className="sliced__whole" aria-hidden="true">
        <Cover seed={seed} title={title} resolved={image} />
      </div>
      {phase === "whole"
        ? null
        : Array.from({ length: SLICES }, (_, i) => (
            <div
              key={i}
              className="slice"
              aria-hidden="true"
              style={
                {
                  "--i": i,
                  "--n": SLICES,
                  "--h1": level(i, 1),
                  "--h2": level(i, 2),
                  "--h3": level(i, 3),
                } as CSSProperties
              }
            >
              <div className="slice__inner">
                <div className="slice__clip">
                  <div className="slice__art" style={paint} />
                </div>
                <div className="slice__shadow">
                  <div className="slice__shade" />
                </div>
              </div>
            </div>
          ))}
    </div>
  );
}
