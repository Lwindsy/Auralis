import { useState, type CSSProperties } from "react";
import { albumSeed, coverArt } from "../../lib/cover.ts";
import type { DeckEntry } from "../../state/deck.ts";
import { useCover } from "../../state/covers.ts";

/**
 * Up to this many sleeves keep the full fan (7° tilt, 6px lift per step from the middle);
 * a larger group keeps the same outer tilt and width and packs its sleeves closer, so every
 * album of the group is still there.
 */
const FAN_FULL = 7;
/** How long the last album's sleeve takes to fly into its place in the stack. */
export const FLIGHT_MS = 820;

/**
 * Every sleeve of the group gathered into a small fan over 这一组听完了. They slide in from the
 * middle; the album that was just left flies in from the page instead (Deck animates the
 * leaving copy), and its own place shows only once it has landed.
 *
 * Each place is an unrotated box (so Deck can measure where to land) holding one painted,
 * rotated card. Cards are drawn once; only transform and opacity animate.
 */
export function GroupStack({
  entries,
  landing,
}: {
  entries: readonly DeckEntry[];
  /** The candidate whose sleeve is flying in, if any. */
  landing: string | null;
}) {
  // The sleeve that flew in keeps its own entrance for as long as the stack is shown:
  // dropping the mark when the flying copy goes would restart the slide-in animation and
  // make the card blink out and replay the end of the move.
  const [landed] = useState(landing);
  if (!entries.length) return null;
  const middle = (entries.length - 1) / 2;
  // Offsets from the middle shrink once the group outgrows the full fan.
  const spread = Math.min(1, (FAN_FULL - 1) / 2 / Math.max(middle, 1));
  return (
    <div
      className="finale__stack"
      aria-hidden="true"
      style={
        {
          "--n": entries.length,
          "--beat": `${Math.min(70, 420 / Math.max(entries.length - 1, 1)).toFixed(1)}ms`,
        } as CSSProperties
      }
    >
      {entries.map((entry, i) => (
        <StackCard
          key={entry.candidateId}
          entry={entry}
          index={i}
          offset={(i - middle) * spread}
          landing={entry.candidateId === landed}
        />
      ))}
    </div>
  );
}

function StackCard({
  entry,
  index,
  offset,
  landing,
}: {
  entry: DeckEntry;
  index: number;
  offset: number;
  landing: boolean;
}) {
  const { title, artist_credit } = entry.card.identity;
  const image = useCover(entry.card.cover_url);
  const paint: CSSProperties = image
    ? { backgroundImage: `url("${image.url}")` }
    : { background: coverArt(albumSeed(title, artist_credit)).background };
  return (
    <div
      className="stack__slot"
      data-candidate={entry.candidateId}
      data-landing={landing || undefined}
      data-heard={entry.heard || undefined}
      style={
        {
          "--i": index,
          "--o": offset,
          "--r": `${(offset * 7).toFixed(1)}deg`,
          "--lift": `${(Math.abs(offset) * 6).toFixed(1)}px`,
        } as CSSProperties
      }
    >
      <div className="stack__card" style={paint} />
    </div>
  );
}
