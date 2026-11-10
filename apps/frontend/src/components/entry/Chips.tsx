import type { Strength } from "../../api/types.ts";
import { albumSeed } from "../../lib/cover.ts";
import { genreName } from "../../lib/text.ts";
import { useStore } from "../../state/hooks.ts";
import { labels } from "../../state/labels.ts";
import { Cover } from "../Cover.tsx";
import { Icon } from "../Icon.tsx";
import { QuietScroll } from "../QuietScroll.tsx";

export type LikedList = "liked_genres" | "liked_albums";

export interface ChipHandlers {
  locked: boolean;
  setStrength: (list: LikedList, id: string, strength: Strength) => void;
  setFocused: (list: LikedList, id: string, focused: boolean) => void;
  remove: (list: LikedList | "disliked_genre_ids", id: string) => void;
}

export interface ChipItem {
  list: LikedList | "disliked_genre_ids";
  id: string;
  strength?: Strength;
  focused?: boolean;
}

const shortId = (id: string) => `${id.slice(0, 8)}…`;

interface ChipName {
  /** Full text for labels and screen readers. */
  name: string;
  seed: string | null;
  /** Shown parts; albums keep title, artist and year apart so none of them is cut. */
  parts: { title: string; artist?: string; year?: number | null };
}

function nameOf(item: ChipItem): ChipName {
  if (item.list === "liked_albums") {
    const label = labels.album(item.id);
    return label
      ? {
          name: `${label.title} — ${label.artist}`,
          seed: albumSeed(label.title, label.artist),
          parts: { title: label.title, artist: label.artist, year: label.year },
        }
      : {
          name: `专辑 ${shortId(item.id)}`,
          seed: item.id,
          parts: { title: `专辑 ${shortId(item.id)}` },
        };
  }
  const known = labels.genre(item.id)?.name;
  const name = known ? genreName(known) : "已选风格";
  return { name, seed: null, parts: { title: name } };
}

/**
 * What has been picked, as small chips that pop in. Liked items carry two marks — 特别喜欢
 * and 本次重点 — and every chip can be removed (which also clears its marks).
 */
export function Chips({
  items,
  handlers,
  label,
}: {
  items: ChipItem[];
  handlers: ChipHandlers;
  label: string;
}) {
  useStore(labels);
  if (!items.length) return null;
  return (
    <QuietScroll className="chips-frame">
      <ul className="chips quiet-scroll" role="list" aria-label={label}>
        {items.map((item) => {
          const { name, seed, parts } = nameOf(item);
          const avoid = item.list === "disliked_genre_ids";
          const liked = item.list !== "disliked_genre_ids" ? item.list : null;
          return (
            <li
              key={`${item.list}:${item.id}`}
              className="chip"
              data-kind={item.list === "liked_albums" ? "album" : "genre"}
              data-avoid={avoid || undefined}
              data-special={item.strength === "special" || undefined}
              data-focused={item.focused || undefined}
            >
              {seed ? (
                <Cover seed={seed} title={name} size="mini" albumId={item.id} />
              ) : null}
              {avoid ? <span className="chip__lead">避开</span> : null}
              <span className="chip__name" title={name}>
                <span className="chip__title">{parts.title}</span>
                {parts.artist ? (
                  <span className="chip__artist">{parts.artist}</span>
                ) : null}
                {parts.year ? (
                  <span className="chip__year mono">{parts.year}</span>
                ) : null}
              </span>
              {liked ? (
                <>
                  <button
                    type="button"
                    className="chip__mark"
                    aria-pressed={item.strength === "special"}
                    aria-label={`${name}特别喜欢`}
                    title="特别喜欢"
                    disabled={handlers.locked}
                    onClick={() =>
                      handlers.setStrength(
                        liked,
                        item.id,
                        item.strength === "special" ? "normal" : "special",
                      )
                    }
                  >
                    <Icon
                      name="heart"
                      size={13}
                      filled={item.strength === "special"}
                    />
                  </button>
                  <button
                    type="button"
                    className="chip__mark"
                    aria-pressed={!!item.focused}
                    aria-label={`${name}本次重点`}
                    title="本次重点"
                    disabled={handlers.locked}
                    onClick={() =>
                      handlers.setFocused(liked, item.id, !item.focused)
                    }
                  >
                    <Icon name="target" size={13} />
                  </button>
                </>
              ) : null}
              <button
                type="button"
                className="chip__mark"
                aria-label={`移除${name}`}
                title="移除"
                disabled={handlers.locked}
                onClick={() => handlers.remove(item.list, item.id)}
              >
                <Icon name="close" size={12} />
              </button>
            </li>
          );
        })}
      </ul>
    </QuietScroll>
  );
}
