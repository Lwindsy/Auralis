import type { CSSProperties } from "react";
import { albumSeed, hash } from "../../lib/cover.ts";
import { Cover } from "../Cover.tsx";

const SLICES = 30;

/**
 * The sleeve as a waveform. It is cut into thin vertical strips that bounce like the bars
 * of an audio meter, then close up into the whole cover. Leaving, it breaks back into a
 * waveform and sinks. Strip heights come from the album's own hash, so every album has its
 * own "voiceprint". A hover sends one ripple across the strips.
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
  return (
    <div className="sliced" role="img" aria-label={`${title} 封面`}>
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
          <div className="slice__art">
            <Cover seed={seed} title={title} url={url} />
          </div>
        </div>
      ))}
    </div>
  );
}
