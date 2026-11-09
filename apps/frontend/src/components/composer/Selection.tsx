import type { DiscoveryInput, Strength } from "../../api/types.ts";
import { albumSeed } from "../../lib/cover.ts";
import { useStore } from "../../state/hooks.ts";
import { labels } from "../../state/labels.ts";
import { Cover } from "../Cover.tsx";
import { Icon } from "../Icon.tsx";

type LikedList = "liked_genres" | "liked_albums";

export interface SelectionHandlers {
  locked: boolean;
  setStrength: (list: LikedList, id: string, strength: Strength) => void;
  setFocused: (list: LikedList, id: string, focused: boolean) => void;
  remove: (list: LikedList | "disliked_genre_ids", id: string) => void;
}

const shortId = (id: string) => `${id.slice(0, 8)}…`;

/** Chosen references read as words in a sentence, each with its own marks. */
export function Selection({
  draft,
  handlers,
}: {
  draft: DiscoveryInput;
  handlers: SelectionHandlers;
}) {
  useStore(labels);
  const liked = [
    ...draft.liked_genres.map((item) => ({
      list: "liked_genres" as const,
      id: item.genre_id,
      strength: item.strength,
      focused: item.focused,
      name:
        labels.genre(item.genre_id)?.name ?? `风格 ${shortId(item.genre_id)}`,
      seed: null as string | null,
    })),
    ...draft.liked_albums.map((item) => {
      const label = labels.album(item.mb_release_group_id);
      return {
        list: "liked_albums" as const,
        id: item.mb_release_group_id,
        strength: item.strength,
        focused: item.focused,
        name: label
          ? `${label.title} — ${label.artist}`
          : `专辑 ${shortId(item.mb_release_group_id)}`,
        seed: label
          ? albumSeed(label.title, label.artist)
          : item.mb_release_group_id,
      };
    }),
  ];
  const disliked = draft.disliked_genre_ids;
  if (!liked.length && !disliked.length) return null;
  return (
    <div className="tokens" role="group" aria-label="已选输入">
      {liked.length ? (
        <p className="tokens__line">
          <span className="tokens__lead">喜欢</span>
          {liked.map((row) => (
            <span
              key={`${row.list}:${row.id}`}
              className="token"
              data-special={row.strength === "special" || undefined}
              data-focused={row.focused || undefined}
            >
              {row.seed ? (
                <Cover seed={row.seed} title={row.name} size="mini" />
              ) : null}
              <span className="token__name">{row.name}</span>
              <span className="token__marks">
                <button
                  type="button"
                  className="token__mark"
                  aria-pressed={row.strength === "special"}
                  aria-label={`${row.name}特别喜欢`}
                  title="特别喜欢"
                  disabled={handlers.locked}
                  onClick={() =>
                    handlers.setStrength(
                      row.list,
                      row.id,
                      row.strength === "special" ? "normal" : "special",
                    )
                  }
                >
                  <Icon
                    name="heart"
                    size={13}
                    filled={row.strength === "special"}
                  />
                </button>
                <button
                  type="button"
                  className="token__mark"
                  aria-pressed={row.focused}
                  aria-label={`${row.name}本次重点`}
                  title="本次重点"
                  disabled={handlers.locked}
                  onClick={() =>
                    handlers.setFocused(row.list, row.id, !row.focused)
                  }
                >
                  <Icon name="target" size={13} />
                </button>
                <button
                  type="button"
                  className="token__mark"
                  aria-label={`移除${row.name}`}
                  title="移除"
                  disabled={handlers.locked}
                  onClick={() => handlers.remove(row.list, row.id)}
                >
                  <Icon name="close" size={12} />
                </button>
              </span>
            </span>
          ))}
        </p>
      ) : null}
      {disliked.length ? (
        <p className="tokens__line">
          <span className="tokens__lead">避开</span>
          {disliked.map((id) => {
            const name = labels.genre(id)?.name ?? `风格 ${shortId(id)}`;
            return (
              <span key={id} className="token token--avoid">
                <span className="token__name">{name}</span>
                <span className="token__marks">
                  <button
                    type="button"
                    className="token__mark"
                    aria-label={`移除${name}`}
                    title="移除"
                    disabled={handlers.locked}
                    onClick={() => handlers.remove("disliked_genre_ids", id)}
                  >
                    <Icon name="close" size={12} />
                  </button>
                </span>
              </span>
            );
          })}
        </p>
      ) : null}
    </div>
  );
}
