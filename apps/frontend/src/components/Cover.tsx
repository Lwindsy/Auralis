import { useEffect, useRef, useState, type CSSProperties } from "react";
import { coverArt } from "../lib/cover.ts";
import { coverUrl, useCover, type LoadedCover } from "../state/covers.ts";

interface CoverProps {
  seed: string;
  title: string;
  /** Accepted for callers; the placeholder sleeve deliberately carries no text. */
  artist?: string;
  url?: string | null;
  albumId?: string;
  resolved?: LoadedCover | null;
  size?: "card" | "thumb" | "mini";
  className?: string;
}

/**
 * Decoded same-origin cover when available; otherwise a deterministic placeholder sleeve
 * that is clearly placeholder artwork, not an imitation of the original cover.
 */
export function Cover({
  seed,
  title,
  url,
  albumId,
  resolved,
  size = "card",
  className,
}: CoverProps) {
  const element = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const requestUrl =
    url ?? (albumId ? coverUrl(albumId, size === "card" ? 500 : 250) : null);
  useEffect(() => {
    if (!requestUrl || !element.current) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setVisible(true);
          observer.disconnect();
        }
      },
      { rootMargin: "300px" },
    );
    observer.observe(element.current);
    return () => observer.disconnect();
  }, [requestUrl]);
  const loaded = useCover(visible ? requestUrl : null);
  const image = resolved ?? loaded;
  const art = coverArt(seed);
  const style = {
    "--cover-bg": art.background,
    "--cover-base": art.base,
    "--cover-accent": art.accent,
    "--cover-ink": art.ink,
  } as CSSProperties;
  return (
    <div
      ref={element}
      className={["cover", `cover--${size}`, className ?? ""].join(" ")}
      style={style}
      data-style={art.style}
    >
      <div className="cover__art" aria-hidden="true" />
      {image ? (
        <img
          key={image.url}
          src={image.url}
          alt={`${title} 封面`}
          decoding="async"
          referrerPolicy="no-referrer"
        />
      ) : null}
    </div>
  );
}
