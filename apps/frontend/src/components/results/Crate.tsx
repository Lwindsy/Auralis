import {
  useEffect,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type ReactNode,
} from "react";
import type { CardSlot, ProductCard } from "../../api/types.ts";
import { albumSeed, coverArt } from "../../lib/cover.ts";
import { ACTIVE_REVIEW } from "../../state/review.ts";
import { Button } from "../Button.tsx";
import { Cover } from "../Cover.tsx";
import { Icon } from "../Icon.tsx";
import { Inline } from "./ReviewText.tsx";

export type Tile =
  | { kind: "card"; slot: CardSlot; card: ProductCard }
  | { kind: "supply"; slot: CardSlot };

const two = (value: number) => String(value).padStart(2, "0");
const NIGHT = { base: "#111111", accent: "#d94f30", ink: "#efece6" };

/** The page chrome (top bar, overscroll, browser UI) follows the room colour. */
export function paintPage(room: string, ink: string) {
  const root = document.documentElement.style;
  root.setProperty("--page-room", room);
  root.setProperty("--page-ink", ink);
  const tag = document.createElement("meta");
  tag.name = "theme-color";
  tag.content = room;
  document.head.appendChild(tag);
  return () => {
    root.removeProperty("--page-room");
    root.removeProperty("--page-ink");
    tag.remove();
  };
}

const seedOf = (card: ProductCard) =>
  albumSeed(card.identity.title, card.identity.artist_credit);

/**
 * The listening room. One record is on the platter at a time and its sleeve colour floods
 * the whole stage; the rest of the group waits in the crate below. Picking a sleeve in the
 * crate (or the arrows, or ← →) puts that record on.
 */
export function Crate({
  tiles,
  locked,
  placeholder,
  topline,
  working,
  onReview,
  onHeard,
  onReplace,
}: {
  tiles: Tile[];
  locked: boolean;
  placeholder: number;
  topline: ReactNode;
  working: ReactNode;
  onReview: (card: ProductCard) => void;
  onHeard: (slotId: string) => void;
  onReplace: (slotId: string) => void;
}) {
  const [requested, setActive] = useState(0);
  const [touchX, setTouchX] = useState<number | null>(null);
  const count = tiles.length;
  const active = count ? Math.min(requested, count - 1) : 0;
  const front = tiles[active];
  const keyOf = (tile: Tile) =>
    `${tile.slot.slot_id}:${tile.kind === "card" ? tile.card.candidate_id : "supply"}`;

  const go = (index: number) => {
    if (count) setActive((index + count) % count);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if ((event.target as HTMLElement).closest("input, textarea")) return;
    if (event.key === "ArrowRight") {
      event.preventDefault();
      go(active + 1);
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      go(active - 1);
    }
  };

  const art = front?.kind === "card" ? coverArt(seedOf(front.card)) : NIGHT;
  const room = {
    "--room": art.base,
    "--room-ink": art.ink,
    "--room-accent": art.accent,
  } as CSSProperties;

  useEffect(() => paintPage(art.base, art.ink), [art.base, art.ink]);

  const idle = (
    <div className="stage__object" aria-hidden="true">
      <div className="platter platter--idle">
        <span className="platter__disc" />
        <span className="cover cover--card cover--blank" />
      </div>
    </div>
  );

  return (
    <section
      className="stage"
      style={room}
      onKeyDown={onKeyDown}
      aria-label="这一组专辑"
    >
      <div className="stage__inner">
        <div className="stage__topline">{topline}</div>

        {count === 0 ? (
          <div className="stage__body">
            <div className="stage__text">{working}</div>
            {idle}
          </div>
        ) : front?.kind === "card" ? (
          <div className="stage__body" key={keyOf(front)}>
            <Notes
              card={front.card}
              locked={locked}
              onReview={() => onReview(front.card)}
              onHeard={() => onHeard(front.slot.slot_id)}
              onReplace={() => onReplace(front.slot.slot_id)}
            />
            <div
              className="stage__object"
              data-flip={count > 1 || undefined}
              title={count > 1 ? "下一张" : undefined}
              onClick={() => go(active + 1)}
              onPointerDown={(event) => setTouchX(event.clientX)}
              onPointerUp={(event) => {
                if (touchX === null) return;
                const dx = event.clientX - touchX;
                setTouchX(null);
                if (Math.abs(dx) > 48) {
                  event.preventDefault();
                  go(active + (dx < 0 ? 1 : -1));
                }
              }}
            >
              <div className="platter">
                <span className="platter__disc" aria-hidden="true">
                  <span className="platter__label" />
                </span>
                <Cover
                  seed={seedOf(front.card)}
                  title={front.card.identity.title}
                  artist={front.card.identity.artist_credit}
                  url={front.card.cover_url}
                />
              </div>
            </div>
          </div>
        ) : (
          <div className="stage__body" key="supply">
            <div className="stage__text notes" role="status">
              <p className="notes__title">
                <span>正在补充</span>
              </p>
              <p className="notes__review">补好后会自动出现。</p>
            </div>
            {idle}
          </div>
        )}

        {count > 0 ? (
          <div className="crate">
            <div className="crate__nav">
              <button
                type="button"
                className="crate__step"
                aria-label="上一张"
                disabled={count < 2}
                onClick={() => go(active - 1)}
              >
                <Icon
                  name="arrowRight"
                  size={18}
                  style={{ transform: "scaleX(-1)" }}
                />
              </button>
              <span className="crate__count mono" aria-live="polite">
                {two(active + 1)} / {two(count)}
              </span>
              <button
                type="button"
                className="crate__step"
                aria-label="下一张"
                disabled={count < 2}
                onClick={() => go(active + 1)}
              >
                <Icon name="arrowRight" size={18} />
              </button>
            </div>
            <ol className="crate__sleeves" aria-label="这一组曲目单">
              {tiles.map((tile, index) => {
                const title =
                  tile.kind === "card" ? tile.card.identity.title : "正在补充";
                const Tag = tile.kind === "card" ? "article" : "div";
                return (
                  <li key={keyOf(tile)}>
                    <Tag
                      className="crate__item"
                      aria-label={title}
                      data-current={index === active || undefined}
                    >
                      <button
                        type="button"
                        className="crate__pick"
                        aria-current={index === active || undefined}
                        onClick={() => go(index)}
                      >
                        {tile.kind === "card" ? (
                          <Cover
                            seed={seedOf(tile.card)}
                            title={tile.card.identity.title}
                            url={tile.card.cover_url}
                            size="mini"
                          />
                        ) : (
                          <span className="cover cover--mini cover--blank" />
                        )}
                        <span className="crate__meta">
                          <span className="crate__title">{title}</span>
                        </span>
                      </button>
                    </Tag>
                  </li>
                );
              })}
            </ol>
          </div>
        ) : (
          <div className="crate crate--ghost" aria-hidden="true">
            {Array.from({ length: placeholder }, (_, index) => (
              <span key={index} className="cover cover--mini cover--blank" />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function Notes({
  card,
  locked,
  onReview,
  onHeard,
  onReplace,
}: {
  card: ProductCard;
  locked: boolean;
  onReview: () => void;
  onHeard: () => void;
  onReplace: () => void;
}) {
  const { title, artist_credit, first_release_year, primary_genres } =
    card.identity;
  const reviewActive = ACTIVE_REVIEW.includes(card.long_review_state);
  const reviewReady = card.long_review_state === "READY";
  return (
    <section className="stage__text notes" aria-label={`${title} 乐评与操作`}>
      <p className="notes__artist">
        {artist_credit}
        <span className="mono">{first_release_year ?? ""}</span>
      </p>
      <h3 className="notes__title">
        <span>{title}</span>
      </h3>
      {primary_genres.length ? (
        <p className="notes__genres" aria-label="主要风格">
          {primary_genres
            .slice(0, 4)
            .map((genre) => genre.name)
            .join(" · ")}
        </p>
      ) : null}
      <p className="notes__review">
        <Inline text={card.short_review.text} />
      </p>
      <div className="notes__actions">
        <Button
          variant="primary"
          size="lg"
          iconAfter="arrowRight"
          locked={locked}
          onClick={onReview}
          data-review-state={card.long_review_state}
          data-review={
            reviewReady ? "ready" : reviewActive ? "active" : undefined
          }
        >
          详细乐评
        </Button>
        <div className="notes__secondary">
          <Button variant="link" locked={locked} onClick={onHeard}>
            已听过
          </Button>
          <Button variant="link" locked={locked} onClick={onReplace}>
            来张新的
          </Button>
        </div>
      </div>
    </section>
  );
}
