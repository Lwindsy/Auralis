import { useEffect, useRef, useState, type CSSProperties } from "react";
import { albumSeed, hash } from "../../lib/cover.ts";
import { Cover } from "../Cover.tsx";

const SLICES = 30;
/** The assembly ends after this long (incoming albums start a little later). */
const ASSEMBLED_MS = 2300;
/** One hover ripple: 900ms per strip plus the stagger across the strips. */
const RIPPLE_MS = 900 + SLICES * 14 + 60;

/**
 * The sleeve as a waveform. It is cut into thin vertical strips that bounce like the bars
 * of an audio meter, then close up into the whole cover. Leaving, it breaks back into a
 * waveform and sinks. Strip heights come from the album's own hash, so every album has its
 * own "voiceprint".
 *
 * Moving onto the cover sends exactly one small ripple across the strips. The ripple runs
 * on an inner layer and is started from script, so moving off the cover does nothing at
 * all: the assembly animation on the strips themselves is never touched again.
 */
export function SlicedCover({
  title,
  artist,
  url,
}: {
  title: string;
  artist: string;
  url: string | null;
}) {
  const seed = albumSeed(title, artist);
  const base = hash(seed);
  const level = (i: number, k: number) => {
    const v = hash(`${base}:${i}:${k}`) / 0xffffffff;
    // A soft envelope keeps the middle louder than the edges, like a real waveform.
    const envelope = 0.45 + 0.55 * Math.sin((Math.PI * (i + 0.5)) / SLICES);
    return (0.12 + 0.88 * v * envelope).toFixed(3);
  };
  const [rippling, setRippling] = useState(0);
  const born = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => {
    born.current = performance.now();
    return () => clearTimeout(timer.current);
  }, []);
  const ripple = () => {
    if (rippling || performance.now() - born.current < ASSEMBLED_MS) return;
    setRippling((value) => value + 1);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setRippling(0), RIPPLE_MS);
  };
  return (
    <div
      className="sliced"
      role="img"
      aria-label={`${title} 封面`}
      data-rippling={rippling ? true : undefined}
      onPointerEnter={(event) => {
        if (event.pointerType !== "touch") ripple();
      }}
    >
      {/* The seamless cover settles over the strips once they have closed up. */}
      <div className="sliced__whole" aria-hidden="true">
        <Cover seed={seed} title={title} url={url} />
      </div>
      {Array.from({ length: SLICES }, (_, i) => (
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
            <div className="slice__art">
              <Cover seed={seed} title={title} url={url} />
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
