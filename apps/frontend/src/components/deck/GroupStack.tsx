import type { CSSProperties } from "react";
import { albumSeed, coverArt } from "../../lib/cover.ts";
import type { DeckEntry } from "../../state/deck.ts";
import { useCover } from "../../state/covers.ts";

/** At most this many sleeves gather over 这一组听完了 (the latest ones). */
export const STACK_MAX = 7;
/** How long the last album's sleeve takes to fly into its place in the stack. */
export const FLIGHT_MS = 820;

/**
 * The group's sleeves gathered into a small fan over 这一组听完了. They slide in from the
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
  const shown = entries.slice(-STACK_MAX);
  if (!shown.length) return null;
  const middle = (shown.length - 1) / 2;
  return (
    <div className="finale__stack" aria-hidden="true">
      {shown.map((entry, i) => (
        <StackCard
          key={entry.candidateId}
          entry={entry}
          index={i}
          offset={i - middle}
          landing={entry.candidateId === landing}
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
