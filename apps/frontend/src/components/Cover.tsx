import { useState, type CSSProperties } from "react";
import { coverArt, safeImage } from "../lib/cover.ts";

interface CoverProps {
  seed: string;
  title: string;
  /** Accepted for callers; the placeholder sleeve deliberately carries no text. */
  artist?: string;
  url?: string | null;
  size?: "card" | "thumb" | "mini";
  className?: string;
}

/**
 * Real HTTPS cover when the API provides one; otherwise a deterministic typographic sleeve
 * that is clearly placeholder artwork, not an imitation of the original cover.
 */
export function Cover({
  seed,
  title,
  url,
  size = "card",
  className,
}: CoverProps) {
  const [failed, setFailed] = useState(false);
  const image = failed ? null : safeImage(url);
  const art = coverArt(seed);
  const style = {
    "--cover-bg": art.background,
    "--cover-base": art.base,
    "--cover-accent": art.accent,
    "--cover-ink": art.ink,
  } as CSSProperties;
  return (
    <div
      className={["cover", `cover--${size}`, className ?? ""].join(" ")}
      style={style}
      data-style={art.style}
    >
      {image ? (
        <img
          src={image}
          alt={`${title} 封面`}
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
          onError={() => setFailed(true)}
        />
      ) : (
        <div className="cover__art" aria-hidden="true" />
      )}
    </div>
  );
}
