import type { Strength } from "../../api/types.ts";
import { albumSeed } from "../../lib/cover.ts";
import { useStore } from "../../state/hooks.ts";
import { labels } from "../../state/labels.ts";
import { Cover } from "../Cover.tsx";
import { Icon } from "../Icon.tsx";

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

function nameOf(item: ChipItem) {
  if (item.list === "liked_albums") {
    const label = labels.album(item.id);
    return label
      ? {
          name: `${label.title} — ${label.artist}`,
          seed: albumSeed(label.title, label.artist),
        }
      : { name: `专辑 ${shortId(item.id)}`, seed: item.id };
  }
  return {
    name: labels.genre(item.id)?.name ?? `风格 ${shortId(item.id)}`,
    seed: null,
  };
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
    <ul className="chips" role="list" aria-label={label}>
      {items.map((item) => {
        const { name, seed } = nameOf(item);
        const avoid = item.list === "disliked_genre_ids";
        const liked = item.list !== "disliked_genre_ids" ? item.list : null;
        return (
          <li
            key={`${item.list}:${item.id}`}
            className="chip"
            data-avoid={avoid || undefined}
            data-special={item.strength === "special" || undefined}
            data-focused={item.focused || undefined}
          >
            {seed ? <Cover seed={seed} title={name} size="mini" /> : null}
            {avoid ? <span className="chip__lead">避开</span> : null}
            <span className="chip__name">{name}</span>
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
  );
}
