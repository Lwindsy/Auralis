import { useEffect } from "react";
import type { Api } from "../../api/client.ts";
import { MODE_COPY } from "../../api/messages.ts";
import type { DiscoveryInput } from "../../api/types.ts";
import { useStore } from "../../state/hooks.ts";
import { labels } from "../../state/labels.ts";
import { Icon } from "../Icon.tsx";

const clip = (text: string, max = 72) => {
  const chars = [...text.trim().replace(/\s+/g, " ")];
  return chars.length > max
    ? `${chars.slice(0, max).join("")}…`
    : chars.join("");
};

/** One-line recap of what this round was asked for, read back from Java's editable input. */
export function RequestSummary({
  api,
  input,
}: {
  api: Api;
  input: DiscoveryInput;
}) {
  useStore(labels);
  useEffect(() => {
    void labels.resolveAlbums(
      api,
      input.liked_albums.map((item) => item.mb_release_group_id),
    );
  }, [api, input.liked_albums]);
  const unknownGenres =
    input.liked_genres.filter((item) => !labels.genre(item.genre_id)).length +
    input.disliked_genre_ids.filter((id) => !labels.genre(id)).length;
  return (
    <ul className="summary" role="list" aria-label="本次需求">
      {input.request_text.trim() ? (
        <li className="summary__item summary__item--quote">
          “{clip(input.request_text)}”
        </li>
      ) : null}
      <li className="summary__item">
        <Icon name="sparkle" size={13} />
        {MODE_COPY[input.mode].label}
      </li>
      {input.liked_genres.map((item) => {
        const label = labels.genre(item.genre_id);
        if (!label) return null;
        return (
          <li
            key={item.genre_id}
            className="summary__item"
            data-special={item.strength === "special" || undefined}
          >
            <Icon name="heart" size={13} filled={item.strength === "special"} />
            {label.name}
            {item.focused ? <span className="summary__focus">重点</span> : null}
          </li>
        );
      })}
      {input.liked_albums.map((item) => {
        const label = labels.album(item.mb_release_group_id);
        return (
          <li
            key={item.mb_release_group_id}
            className="summary__item"
            data-special={item.strength === "special" || undefined}
          >
            <Icon name="disc" size={13} />
            {label ? clip(label.title, 28) : "喜欢的专辑"}
            {item.focused ? <span className="summary__focus">重点</span> : null}
          </li>
        );
      })}
      {input.disliked_genre_ids.map((id) => {
        const label = labels.genre(id);
        if (!label) return null;
        return (
          <li key={id} className="summary__item summary__item--muted">
            <Icon name="ban" size={13} />
            {label.name}
          </li>
        );
      })}
      {unknownGenres ? (
        <li className="summary__item summary__item--muted">
          另有 {unknownGenres} 个风格
        </li>
      ) : null}
      {input.audience_min_listeners !== undefined ? (
        <li className="summary__item summary__item--muted tabular">
          听众 ≥ {input.audience_min_listeners}
        </li>
      ) : null}
    </ul>
  );
}
