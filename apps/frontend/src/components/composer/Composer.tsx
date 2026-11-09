import { useEffect, useId, useMemo, useState, type FormEvent } from "react";
import type { Api } from "../../api/client.ts";
import type {
  CatalogAlbum,
  DiscoveryInput,
  GenreNode,
  Strength,
} from "../../api/types.ts";
import {
  MAX_ALBUMS,
  MAX_TEXT,
  codepoints,
  emptyInput,
  hasInput,
} from "../../state/input.ts";
import { labels } from "../../state/labels.ts";
import { Button } from "../Button.tsx";
import { AlbumSearch } from "./AlbumSearch.tsx";
import { GenreBrowser, type GenreHandlers } from "./GenreBrowser.tsx";
import { ModePicker } from "./ModePicker.tsx";
import { Selection, type SelectionHandlers } from "./Selection.tsx";

export interface ComposerProps {
  api: Api;
  initial: DiscoveryInput | null;
  variant: "create" | "edit";
  locked: boolean;
  onSubmit: (input: DiscoveryInput) => void | Promise<unknown>;
  onCancel?: () => void;
}

type LikedList = "liked_genres" | "liked_albums";

const audienceError = (text: string) => {
  if (text === "") return null;
  const value = Number(text);
  return Number.isInteger(value) && value >= 1 && value <= 2147483647
    ? null
    : "请输入 1 以上的整数，或留空使用默认门槛。";
};

export function Composer({
  api,
  initial,
  variant,
  locked,
  onSubmit,
  onCancel,
}: ComposerProps) {
  const [draft, setDraft] = useState<DiscoveryInput>(() =>
    structuredClone(initial ?? emptyInput()),
  );
  const [audience, setAudience] = useState(() =>
    initial?.audience_min_listeners === undefined
      ? ""
      : String(initial.audience_min_listeners),
  );
  const [moreOpen, setMoreOpen] = useState(
    () => initial?.audience_min_listeners !== undefined,
  );
  const [tab, setTab] = useState<"genres" | "albums" | null>(null);
  const ids = useId();

  useEffect(() => {
    void labels.resolveAlbums(
      api,
      (initial?.liked_albums ?? []).map((item) => item.mb_release_group_id),
    );
  }, [api, initial]);

  const length = codepoints(draft.request_text);
  const tooLong = length > MAX_TEXT;
  const audienceProblem = audienceError(audience);
  const ready = hasInput(draft);
  const canSubmit = ready && !tooLong && !audienceProblem;

  const liked = useMemo(
    () => new Set(draft.liked_genres.map((item) => item.genre_id)),
    [draft.liked_genres],
  );
  const disliked = useMemo(
    () => new Set(draft.disliked_genre_ids),
    [draft.disliked_genre_ids],
  );
  const albums = useMemo(
    () => new Set(draft.liked_albums.map((item) => item.mb_release_group_id)),
    [draft.liked_albums],
  );

  const update = (change: (next: DiscoveryInput) => void) =>
    setDraft((current) => {
      const next = structuredClone(current);
      change(next);
      return next;
    });

  const like = (node: GenreNode) =>
    update((next) => {
      next.disliked_genre_ids = next.disliked_genre_ids.filter(
        (id) => id !== node.genre_id,
      );
      if (!next.liked_genres.some((item) => item.genre_id === node.genre_id))
        next.liked_genres.push({
          genre_id: node.genre_id,
          strength: "normal",
          focused: false,
        });
    });
  const removeGenre = (id: string) =>
    update((next) => {
      next.liked_genres = next.liked_genres.filter(
        (item) => item.genre_id !== id,
      );
      next.disliked_genre_ids = next.disliked_genre_ids.filter(
        (value) => value !== id,
      );
    });

  const genreHandlers: GenreHandlers = {
    liked,
    disliked,
    locked,
    onPick: (node) => {
      if (liked.has(node.genre_id)) {
        removeGenre(node.genre_id);
        return false;
      }
      like(node);
      return true;
    },
    onToggleLike: (node) =>
      liked.has(node.genre_id) ? removeGenre(node.genre_id) : like(node),
    onToggleDislike: (node) =>
      disliked.has(node.genre_id)
        ? removeGenre(node.genre_id)
        : update((next) => {
            next.liked_genres = next.liked_genres.filter(
              (item) => item.genre_id !== node.genre_id,
            );
            next.disliked_genre_ids.push(node.genre_id);
          }),
  };

  const addAlbum = (album: CatalogAlbum) =>
    update((next) => {
      if (next.liked_albums.length >= MAX_ALBUMS) return;
      if (
        next.liked_albums.some(
          (item) => item.mb_release_group_id === album.mb_release_group_id,
        )
      )
        return;
      next.liked_albums.push({
        mb_release_group_id: album.mb_release_group_id,
        strength: "normal",
        focused: false,
      });
    });

  const keyOf = (list: LikedList) =>
    list === "liked_genres" ? "genre_id" : "mb_release_group_id";
  const selectionHandlers: SelectionHandlers = {
    locked,
    setStrength: (list, id, strength: Strength) =>
      update((next) => {
        const key = keyOf(list);
        for (const item of next[list] as Record<string, unknown>[])
          if (item[key] === id) item.strength = strength;
      }),
    setFocused: (list, id, focused) =>
      update((next) => {
        const key = keyOf(list);
        for (const item of next[list] as Record<string, unknown>[])
          if (item[key] === id) item.focused = focused;
      }),
    remove: (list, id) =>
      update((next) => {
        if (list === "disliked_genre_ids")
          next.disliked_genre_ids = next.disliked_genre_ids.filter(
            (value) => value !== id,
          );
        else if (list === "liked_genres")
          next.liked_genres = next.liked_genres.filter(
            (item) => item.genre_id !== id,
          );
        else
          next.liked_albums = next.liked_albums.filter(
            (item) => item.mb_release_group_id !== id,
          );
      }),
  };

  const submit = (event?: FormEvent) => {
    event?.preventDefault();
    if (locked || !canSubmit) return;
    const input = structuredClone(draft);
    if (audience === "") delete input.audience_min_listeners;
    else input.audience_min_listeners = Number(audience);
    void onSubmit(input);
  };

  const genreCount =
    draft.liked_genres.length + draft.disliked_genre_ids.length;
  const albumCount = draft.liked_albums.length;
  const textId = `${ids}-text`;

  const adders = (
    [
      ["genres", "风格", genreCount],
      ["albums", "专辑", albumCount],
    ] as const
  ).map(([value, label, count]) => (
    <button
      key={value}
      type="button"
      id={`${ids}-tab-${value}`}
      className="option"
      aria-expanded={tab === value}
      aria-controls={`${ids}-panel-${value}`}
      onClick={() => setTab(tab === value ? null : value)}
    >
      <span className="option__plus" aria-hidden="true" />
      {label}
      {count ? <span className="option__count">{count}</span> : null}
    </button>
  ));

  return (
    <form className="composer" onSubmit={submit} noValidate>
      <fieldset className="composer__body" disabled={locked}>
        <legend className="visually-hidden">推荐需求</legend>
        <div className="ask" data-invalid={tooLong || undefined}>
          <textarea
            id={textId}
            className="ask__input"
            aria-label="这次想听的音乐"
            placeholder="说说这次想听什么"
            rows={1}
            value={draft.request_text}
            aria-invalid={tooLong || undefined}
            onChange={(event) => {
              const value = event.target.value;
              update((next) => {
                next.request_text = value;
              });
            }}
            onKeyDown={(event) => {
              // Enter sends, Shift+Enter breaks the line; never while an IME is composing.
              if (
                event.key === "Enter" &&
                !event.shiftKey &&
                !event.nativeEvent.isComposing
              ) {
                event.preventDefault();
                submit();
              }
            }}
          />
          <Button
            type="submit"
            variant="primary"
            iconOnly
            icon="arrowRight"
            className="ask__go"
            disabled={!canSubmit}
            locked={locked}
          >
            {variant === "edit" ? "提交新需求" : "开始发现"}
          </Button>
        </div>
        {tooLong ? (
          <p className="ask__error">
            文字最多 {MAX_TEXT} 个字符，当前 {length}。
          </p>
        ) : null}

        {variant === "create" && !draft.request_text ? (
          <div className="suggest" aria-label="试试这样说">
            {EXAMPLES.slice(0, 4).map((text) => (
              <button
                key={text}
                type="button"
                className="suggest__chip"
                onClick={() =>
                  update((next) => {
                    next.request_text = text;
                  })
                }
              >
                {text}
              </button>
            ))}
          </div>
        ) : null}

        <div className="options">
          <ModePicker
            value={draft.mode}
            disabled={locked}
            onChange={(mode) =>
              update((next) => {
                next.mode = mode;
              })
            }
          />
          <span className="options__rule" aria-hidden="true" />
          {adders}
          <button
            type="button"
            className="option option--quiet"
            aria-expanded={moreOpen}
            aria-controls={`${ids}-more`}
            onClick={() => setMoreOpen((value) => !value)}
          >
            更多设置
            {audience && !moreOpen ? (
              <span className="option__count">≥{audience}</span>
            ) : null}
          </button>
        </div>

        <Selection draft={draft} handlers={selectionHandlers} />

        <div
          className="drawer"
          role="region"
          id={`${ids}-panel-genres`}
          aria-label="风格"
          hidden={tab !== "genres"}
        >
          <GenreBrowser api={api} handlers={genreHandlers} />
        </div>
        <div
          className="drawer"
          role="region"
          id={`${ids}-panel-albums`}
          aria-label="专辑"
          hidden={tab !== "albums"}
        >
          <AlbumSearch
            api={api}
            selected={albums}
            locked={locked}
            onAdd={addAlbum}
          />
        </div>
        <div
          className="drawer drawer--plain"
          id={`${ids}-more`}
          hidden={!moreOpen}
        >
          <label className="field">
            <span className="field__label">最低听众数（可选）</span>
            <input
              className="field__input tabular"
              type="number"
              inputMode="numeric"
              min={1}
              max={2147483647}
              step={1}
              placeholder="留空使用默认门槛"
              value={audience}
              aria-invalid={!!audienceProblem || undefined}
              onChange={(event) => setAudience(event.target.value.trim())}
            />
            <span className={audienceProblem ? "field__error" : "field__hint"}>
              {audienceProblem ??
                "按 ListenBrainz 样本听众数过滤，不代表全网热度。"}
            </span>
          </label>
        </div>
      </fieldset>

      {onCancel ? (
        <footer className="composer__footer">
          <Button variant="link" onClick={onCancel} disabled={locked}>
            取消修改
          </Button>
        </footer>
      ) : null}
    </form>
  );
}

const EXAMPLES = [
  "适合深夜开车的电子乐，别太吵",
  "像《In Rainbows》那样温暖、带点忧郁",
  "下雨的周末下午，想听爵士钢琴",
  "第一次听实验音乐，别太难进入",
  "随便，给我一点惊喜",
];
