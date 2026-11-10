import { useEffect, useMemo, useState } from "react";
import type { ProductCard } from "../../api/types.ts";
import {
  imageSwatches,
  placeholderSwatches,
  roomFor,
  type Room,
} from "../../lib/color.ts";
import { albumSeed, coverArt, safeImage } from "../../lib/cover.ts";

/**
 * The room colour for the album on show. Placeholder sleeves are measured from their own
 * field and mark; a real cover (when the API sends one) is sampled once it has loaded and
 * replaces the estimate. Either way the choice is made by `roomFor`.
 */
export function useRoom(card: ProductCard | null): Room | null {
  const title = card?.identity.title ?? "";
  const artist = card?.identity.artist_credit ?? "";
  const estimate = useMemo(() => {
    if (!card) return null;
    const art = coverArt(albumSeed(title, artist));
    return roomFor(placeholderSwatches(art.base, art.accent, art.markShare));
  }, [card, title, artist]);
  const url = safeImage(card?.cover_url);
  const [sampled, setSampled] = useState<{ url: string; room: Room } | null>(
    null,
  );
  useEffect(() => {
    if (!url) return;
    let live = true;
    imageSwatches(url)
      .then((swatches) => {
        if (live && swatches.length)
          setSampled({ url, room: roomFor(swatches) });
      })
      .catch(() => {
        // Unreadable cover (no CORS, offline): the estimate stays.
      });
    return () => {
      live = false;
    };
  }, [url]);
  return url && sampled?.url === url ? sampled.room : estimate;
}
