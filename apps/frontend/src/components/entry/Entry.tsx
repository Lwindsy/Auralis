import {
  useEffect,
  useMemo,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import type { Api } from "../../api/client.ts";
import type {
  CatalogAlbum,
  DiscoveryInput,
  GenreNode,
  Strength,
} from "../../api/types.ts";
import {
  draftFrom,
  reachToListeners,
  type EntryDraft,
} from "../../state/draft.ts";
import {
  MAX_ALBUMS,
  MAX_TEXT,
  codepoints,
  emptyInput,
  hasInput,
} from "../../state/input.ts";
import { useCopy } from "../../i18n/index.ts";
import { labels } from "../../state/labels.ts";
import { Icon } from "../Icon.tsx";
import { AlbumShelf } from "./AlbumShelf.tsx";
import {
  Chips,
  type ChipHandlers,
  type ChipItem,
  type LikedList,
} from "./Chips.tsx";
import { GenrePanel, type GenreHandlers } from "./GenrePanel.tsx";
import { Glyph } from "./Glyph.tsx";
import { setTerrain } from "../motion/Contours.tsx";
import { ModeWords } from "./ModeWords.tsx";
import { Reach } from "./Reach.tsx";

export type Door = "text" | "genres" | "albums";
const DOORS: Door[] = ["text", "genres", "albums"];

export interface EntryProps {
  api: Api;
  initial: DiscoveryInput | null;
  saved: EntryDraft | null;
  onDraft: (draft: EntryDraft) => void;
  locked: boolean;
  leaving: boolean;
  notice: ReactNode;
  onResume: (() => void) | null;
  onSubmit: (input: DiscoveryInput) => void;
}

/**
 * The start page. Three doors — words, genres, albums — sit side by side. Opening one
 * widens it and folds the others into slim spines; switching doors swaps the roles in one
 * motion. Everything enters on load one piece after another and is cut away when the
 * request goes out.
 */
export function Entry({
  api,
  initial,
  saved,
  onDraft,
  locked,
  leaving,
  notice,
  onResume,
  onSubmit,
}: EntryProps) {
  const [form, setForm] = useState<EntryDraft>(() =>
    structuredClone(saved ?? draftFrom(initial ?? emptyInput())),
  );
  const [active, setActive] = useState<Door | null>(null);
  const words = useCopy();
  const FACE = words.entry.doors;
  const draft = form.input;

  useEffect(() => {
    void labels.resolveAlbums(
      api,
      draft.liked_albums.map((item) => item.mb_release_group_id),
    );
    void labels.resolveGenres(api, [
      ...draft.liked_genres.map((item) => item.genre_id),
      ...draft.disliked_genre_ids,
    ]);
    // Only names of the starting selection need resolving; new picks carry their labels.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api]);

  const change = (edit: (next: EntryDraft) => void) => {
    const next = structuredClone(form);
    edit(next);
    setForm(next);
    onDraft(next);
  };
  const update = (edit: (next: DiscoveryInput) => void) =>
    change((next) => edit(next.input));

  // The landscape behind the page answers the exploration mode.
  useEffect(() => {
    setTerrain(draft.mode);
  }, [draft.mode]);

  const length = codepoints(draft.request_text);
  const tooLong = length > MAX_TEXT;
  const ready = hasInput(draft);
  const canSubmit = ready && !tooLong;

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
  const chipHandlers: ChipHandlers = {
    locked,
    setStrength: (list, id, strength: Strength) =>
      update((next) => {
        for (const item of next[list] as Record<string, unknown>[])
          if (item[keyOf(list)] === id) item.strength = strength;
      }),
    setFocused: (list, id, focused) =>
      update((next) => {
        for (const item of next[list] as Record<string, unknown>[])
          if (item[keyOf(list)] === id) item.focused = focused;
      }),
    remove: (list, id) =>
      update((next) => {
        if (list === "disliked_genre_ids")
          next.disliked_genre_ids = next.disliked_genre_ids.filter(
            (v) => v !== id,
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

  const genreChips: ChipItem[] = [
    ...draft.liked_genres.map((item) => ({
      list: "liked_genres" as const,
      id: item.genre_id,
      strength: item.strength,
      focused: item.focused,
    })),
    ...draft.disliked_genre_ids.map((id) => ({
      list: "disliked_genre_ids" as const,
      id,
    })),
  ];
  const albumChips: ChipItem[] = draft.liked_albums.map((item) => ({
    list: "liked_albums" as const,
    id: item.mb_release_group_id,
    strength: item.strength,
    focused: item.focused,
  }));

  const submit = () => {
    if (locked || !canSubmit) return;
    const input = structuredClone(draft);
    const { min, max } = reachToListeners(form.reach);
    input.audience_min_listeners = min;
    if (max === undefined) delete input.audience_max_listeners;
    else input.audience_max_listeners = max;
    onSubmit(input);
  };

  // Escape, or a press anywhere outside the open door, folds it back.
  useEffect(() => {
    if (!active) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) setActive(null);
    };
    const onPress = (event: PointerEvent) => {
      const target = event.target as Element | null;
      if (
        !target?.closest?.('.door[data-state="open"], .door__face, .genre-card')
      )
        setActive(null);
    };
    window.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onPress);
    return () => {
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onPress);
    };
  }, [active]);

  const counts: Record<Door, number> = {
    text: draft.request_text.trim() ? 1 : 0,
    genres: genreChips.length,
    albums: albumChips.length,
  };
  const preview = draft.request_text.trim();

  const body = (door: Door) => {
    if (door === "text")
      return (
        <div className="words">
          <textarea
            className="words__input"
            aria-label={words.entry.textLabel}
            placeholder={words.entry.textPlaceholder}
            value={draft.request_text}
            aria-invalid={tooLong || undefined}
            autoFocus
            onChange={(event) => {
              const value = event.target.value;
              update((next) => {
                next.request_text = value;
              });
            }}
            onKeyDown={(event) => {
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
          {tooLong ? (
            <p className="words__error">
              {words.entry.tooLong(MAX_TEXT, length)}
            </p>
          ) : null}
          {!draft.request_text ? (
            <div
              className="words__examples"
              aria-label={words.entry.examplesLabel}
            >
              {words.entry.examples.map((text, index) => (
                <button
                  key={text}
                  type="button"
                  className="words__example"
                  style={{ "--i": index } as CSSProperties}
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
        </div>
      );
    if (door === "genres")
      return (
        <>
          <Chips
            items={genreChips}
            handlers={chipHandlers}
            label={words.entry.pickedGenres}
            onClear={() =>
              update((next) => {
                next.liked_genres = [];
                next.disliked_genre_ids = [];
              })
            }
          />
          <GenrePanel api={api} handlers={genreHandlers} />
        </>
      );
    return (
      <>
        <Chips
          items={albumChips}
          handlers={chipHandlers}
          label={words.entry.pickedAlbums}
          onClear={() =>
            update((next) => {
              next.liked_albums = [];
            })
          }
        />
        <AlbumShelf
          api={api}
          selected={albums}
          locked={locked}
          onAdd={addAlbum}
        />
      </>
    );
  };

  return (
    <section
      className="entry"
      data-active={active ?? undefined}
      data-leaving={leaving || undefined}
      aria-labelledby="entry-title"
    >
      <header className="entry__head">
        <h1 id="entry-title" className="entry__title" aria-label="Auralis">
          {[..."Auralis"].map((char, index) => (
            <span
              key={index}
              aria-hidden="true"
              style={{ "--i": index } as CSSProperties}
            >
              {char}
            </span>
          ))}
          <svg
            className="entry__signature"
            viewBox="0 0 300 24"
            aria-hidden="true"
            focusable="false"
          >
            <path
              pathLength="1"
              d="M2 14 C 30 14, 38 4, 58 6 S 86 22, 112 16 S 150 2, 176 8 S 214 22, 240 14 S 280 6, 298 10"
            />
          </svg>
        </h1>
        <p className="entry__hint">{words.entry.hint}</p>
        {notice}
        {onResume ? (
          <button type="button" className="entry__resume" onClick={onResume}>
            {words.entry.resume}
            <Icon name="arrowRight" size={15} />
          </button>
        ) : null}
      </header>

      <div className="doors">
        {DOORS.map((door, index) => {
          const state = active === door ? "open" : active ? "folded" : "idle";
          const id = `door-${door}`;
          return (
            <section
              key={door}
              className="door"
              data-door={door}
              data-state={state}
              style={{ "--i": index } as CSSProperties}
            >
              <button
                type="button"
                className="door__face"
                aria-label={
                  FACE[door].label +
                  (counts[door] && door !== "text"
                    ? words.entry.selectedCount(counts[door])
                    : "")
                }
                aria-expanded={state === "open"}
                aria-controls={id}
                onClick={() => setActive(state === "open" ? null : door)}
              >
                {/* Three faces share the door and cross-fade while it resizes. */}
                <span className="face face--idle" aria-hidden="true">
                  <Glyph kind={door} />
                  <span className="face__label">{FACE[door].label}</span>
                  <span className="face__hint">
                    {door === "text" && preview
                      ? words.entry.quote(preview)
                      : FACE[door].hint}
                  </span>
                </span>
                <span className="face face--open" aria-hidden="true">
                  <Glyph kind={door} />
                  <span className="face__label">{FACE[door].label}</span>
                  <span className="face__fold">{words.entry.fold}</span>
                </span>
                <span className="face face--spine" aria-hidden="true">
                  <Glyph kind={door} />
                  <span className="face__spine">{words.entry.spine[door]}</span>
                </span>
                {counts[door] && door !== "text" ? (
                  <span className="door__count mono" aria-hidden="true">
                    {counts[door]}
                  </span>
                ) : null}
              </button>
              <div className="door__body" id={id} hidden={state !== "open"}>
                {state === "open" ? body(door) : null}
              </div>
            </section>
          );
        })}
      </div>

      <footer className="dock">
        <ModeWords
          value={draft.mode}
          disabled={locked}
          onChange={(mode) =>
            update((next) => {
              next.mode = mode;
            })
          }
        />
        <Reach
          value={form.reach}
          disabled={locked}
          onChange={(value) =>
            change((next) => {
              next.reach = value;
            })
          }
        />
        <button
          type="button"
          className="go"
          aria-label={words.entry.start}
          data-ready={canSubmit || undefined}
          disabled={!canSubmit}
          aria-busy={locked || undefined}
          onClick={submit}
        >
          <svg viewBox="0 0 48 48" aria-hidden="true" focusable="false">
            <circle
              className="go__ring"
              cx="24"
              cy="24"
              r="22"
              pathLength="1"
            />
            <path className="go__arrow" d="M15 24h18m-7-7 7 7-7 7" />
          </svg>
        </button>
      </footer>
    </section>
  );
}
