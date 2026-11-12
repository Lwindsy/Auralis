import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type FocusEvent as ReactFocusEvent,
} from "react";
import { createPortal } from "react-dom";
import type { Api } from "../../api/client.ts";
import { message } from "../../api/messages.ts";
import type { GenreNode, GenreSearchResult } from "../../api/types.ts";
import { genreDisplayName, genreNote } from "../../content/genreNotes.ts";
import { copy, useCopy } from "../../i18n/index.ts";
import { useDebounced } from "../../state/hooks.ts";
import { labels } from "../../state/labels.ts";
import { Icon } from "../Icon.tsx";
import { QuietScroll } from "../QuietScroll.tsx";
import { SearchField } from "./SearchField.tsx";
import { usePaged } from "./usePaged.ts";

export interface GenreHandlers {
  liked: ReadonlySet<string>;
  disliked: ReadonlySet<string>;
  locked: boolean;
  onPick: (node: GenreNode) => boolean;
  onToggleLike: (node: GenreNode) => void;
  onToggleDislike: (node: GenreNode) => void;
}

/** Display-only group for root genres with no sub-genres; never submitted. */
export const UNIQUE_GROUP = "Unique";
const PAGE = "50";

function useGenrePages(
  api: Api,
  key: string | null,
  query: string,
  parentId: string | null,
) {
  const load = useCallback(
    async (offset: number, signal: AbortSignal) => {
      const page = await api.request<GenreSearchResult>(
        "GET",
        "/v1/catalog/genres",
        {
          query: {
            ...(query ? { query } : {}),
            ...(parentId ? { parent_id: parentId } : {}),
            offset: String(offset),
            limit: PAGE,
          },
          signal,
        },
      );
      labels.rememberGenres(page.items);
      return page;
    },
    [api, query, parentId],
  );
  return usePaged<GenreNode>(key, load);
}

type Pages = ReturnType<typeof useGenrePages>;

/** Lists are shown whole: further pages load on their own until the list is complete. */
function useLoadAll(pages: Pages) {
  const { status, next, loadingMore, error, loadMore } = pages;
  useEffect(() => {
    if (status === "ready" && next !== null && !loadingMore && !error)
      loadMore();
  }, [status, next, loadingMore, error, loadMore]);
}

interface Anchor {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

interface Hover {
  node: GenreNode;
  parent: GenreNode | null;
  anchor: Anchor;
}

type HoverHandlers = {
  show: (node: GenreNode, parent: GenreNode | null, anchor: Anchor) => void;
  hide: () => void;
};

function relation(node: GenreNode, parent: GenreNode | null) {
  const words = copy().genres;
  if (parent)
    return words.childOf(genreDisplayName(parent.genre_id, parent.name));
  const parents = node.parent_ids.flatMap((id) => {
    const name = labels.genre(id)?.name;
    return name ? [genreDisplayName(id, name)] : [];
  });
  if (parents.length) return words.partOf(parents);
  return node.has_children ? words.family : words.standalone;
}

/**
 * Genre browser: families first, stand-alone genres gathered in Unique. Hovering a name
 * opens a small card with a short note; opening a family lets all of its sub-genres grow
 * out of the parent along a drawn branch, in place (the outer list is the only scroll).
 */
export function GenrePanel({
  api,
  handlers,
}: {
  api: Api;
  handlers: GenreHandlers;
}) {
  const [query, setQuery] = useState("");
  const [immediate, setImmediate] = useState<string | null>(null);
  const words = useCopy().genres;
  const debounced = useDebounced(query.trim(), 220);
  const effective = immediate ?? debounced;
  const pages = useGenrePages(api, `q:${effective}`, effective, null);
  useLoadAll(pages);

  const [hover, setHover] = useState<Hover | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const hoverHandlers: HoverHandlers = {
    show: (node, parent, anchor) => {
      clearTimeout(timer.current);
      timer.current = setTimeout(() => setHover({ node, parent, anchor }), 240);
    },
    hide: () => {
      clearTimeout(timer.current);
      timer.current = setTimeout(() => setHover(null), 90);
    },
  };

  return (
    <div className="genres">
      <SearchField
        id="genres-query"
        label={words.search}
        placeholder={words.placeholder}
        value={query}
        onChange={(value) => {
          setQuery(value);
          setImmediate(null);
        }}
        onSubmit={() => setImmediate(query.trim())}
      />
      <QuietScroll className="genres__frame">
        <div
          className="genres__list quiet-scroll"
          aria-live="polite"
          aria-busy={pages.status === "loading"}
          onScroll={() => setHover(null)}
        >
          {effective ? (
            <GenreList
              api={api}
              pages={pages}
              handlers={handlers}
              hover={hoverHandlers}
              parent={null}
              empty={words.noMatch}
            />
          ) : (
            <Families
              api={api}
              pages={pages}
              handlers={handlers}
              hover={hoverHandlers}
            />
          )}
        </div>
      </QuietScroll>
      {hover ? <HoverCard hover={hover} key={hover.node.genre_id} /> : null}
    </div>
  );
}

/**
 * The note grows out of the genre's own name: a hairline is drawn from the end of the
 * name, the card wipes open from that side, its outline traces itself, and the text
 * appears character by character as if being typeset. Placed beside the row (or under
 * it near the edge of the screen), never at the cursor.
 */
function HoverCard({ hover }: { hover: Hover }) {
  useCopy();
  const note = genreNote(hover.node.genre_id);
  const width = 288;
  const { anchor } = hover;
  const mid = (anchor.top + anchor.bottom) / 2;
  const side = anchor.right + 44 + width < window.innerWidth - 16;
  const left = side ? anchor.right + 40 : Math.max(16, anchor.left - 8);
  const top = side
    ? Math.max(16, Math.min(mid - 40, window.innerHeight - 220))
    : anchor.bottom + 14;
  // Portalled: the doors use backdrop blur, which would trap a fixed element inside.
  return createPortal(
    <>
      {side ? (
        <svg
          className="genre-link"
          style={
            { left: anchor.right + 6, top: mid - 1, width: 34 } as CSSProperties
          }
          viewBox="0 0 34 2"
          aria-hidden="true"
        >
          <line x1="0" y1="1" x2="34" y2="1" pathLength="1" />
          <circle cx="1" cy="1" r="1" />
        </svg>
      ) : null}
      <div
        className="genre-card"
        role="tooltip"
        data-side={side ? "right" : "below"}
        style={{ left, top, width } as CSSProperties}
      >
        <svg className="genre-card__outline" aria-hidden="true">
          <rect x="0.5" y="0.5" pathLength="1" />
        </svg>
        <p className="genre-card__name">
          {genreDisplayName(hover.node.genre_id, hover.node.name)}
        </p>
        {note ? (
          <p className="genre-card__note" aria-label={note}>
            {[...note].map((char, i) => (
              <span
                key={i}
                aria-hidden="true"
                style={{ "--c": i } as CSSProperties}
              >
                {char}
              </span>
            ))}
          </p>
        ) : null}
        <p
          className="genre-card__relation mono"
          style={{ "--c": note ? [...note].length : 0 } as CSSProperties}
        >
          {relation(hover.node, hover.parent)}
        </p>
      </div>
    </>,
    document.body,
  );
}

function Families({
  api,
  pages,
  handlers,
  hover,
}: {
  api: Api;
  pages: Pages;
  handlers: GenreHandlers;
  hover: HoverHandlers;
}) {
  const [uniqueOpen, setUniqueOpen] = useState(false);
  const words = useCopy().genres;
  if (pages.status !== "ready" || !pages.items.length)
    return (
      <GenreList
        api={api}
        pages={pages}
        handlers={handlers}
        hover={hover}
        parent={null}
        empty={words.none}
      />
    );
  const families = pages.items.filter((node) => node.has_children);
  const loners = pages.items.filter((node) => !node.has_children);
  const complete = pages.next === null;
  return (
    <>
      <ul className="tree" role="list" aria-label={words.families}>
        {families.map((node, index) => (
          <GenreRow
            key={node.genre_id}
            api={api}
            node={node}
            handlers={handlers}
            hover={hover}
            parent={null}
            index={index}
          />
        ))}
        {complete && loners.length ? (
          <li className="tree__item">
            <div className="tree__row tree__row--group">
              <button
                type="button"
                className="tree__toggle"
                aria-expanded={uniqueOpen}
                aria-label={
                  uniqueOpen
                    ? words.collapse(UNIQUE_GROUP)
                    : words.expand(UNIQUE_GROUP)
                }
                onClick={() => setUniqueOpen((value) => !value)}
              >
                <Icon name="chevronRight" size={15} />
              </button>
              <button
                type="button"
                className="tree__name tree__name--group"
                aria-expanded={uniqueOpen}
                onClick={() => setUniqueOpen((value) => !value)}
              >
                {UNIQUE_GROUP}
                <span className="tree__count mono">{loners.length}</span>
              </button>
            </div>
            {uniqueOpen ? (
              <div className="branch">
                <p className="branch__note">{words.uniqueNote}</p>
                <ul className="tree" role="list" aria-label={words.loners}>
                  {loners.map((node, index) => (
                    <GenreRow
                      key={node.genre_id}
                      api={api}
                      node={node}
                      handlers={handlers}
                      hover={hover}
                      parent={null}
                      index={index}
                    />
                  ))}
                </ul>
              </div>
            ) : null}
          </li>
        ) : null}
      </ul>
      {pages.error ? (
        <p className="genres__message" role="alert">
          {message(pages.error)}{" "}
          <button type="button" className="text-link" onClick={pages.loadMore}>
            {words.loadMore}
          </button>
        </p>
      ) : !complete ? (
        <p className="genres__message" role="status">
          {words.loadingAll}
        </p>
      ) : null}
    </>
  );
}

function GenreList({
  api,
  pages,
  handlers,
  hover,
  parent,
  empty,
}: {
  api: Api;
  pages: Pages;
  handlers: GenreHandlers;
  hover: HoverHandlers;
  parent: GenreNode | null;
  empty: string;
}) {
  const words = useCopy().genres;
  if (pages.status === "loading" || pages.status === "idle")
    return (
      <div
        className="genres__skeleton"
        role="status"
        aria-label={words.searching}
      >
        {Array.from({ length: parent ? 3 : 6 }, (_, i) => (
          <span key={i} style={{ width: `${70 - i * 8}%` }} />
        ))}
      </div>
    );
  if (pages.status === "error")
    return (
      <p className="genres__message" role="alert">
        {message(pages.error)}{" "}
        <button type="button" className="text-link" onClick={pages.retry}>
          {words.retry}
        </button>
      </p>
    );
  if (!pages.items.length) return <p className="genres__message">{empty}</p>;
  return (
    <>
      <ul className="tree" role="list">
        {pages.items.map((node, index) => (
          <GenreRow
            key={node.genre_id}
            api={api}
            node={node}
            handlers={handlers}
            hover={hover}
            parent={parent}
            index={index}
          />
        ))}
      </ul>
      {pages.next !== null && pages.error ? (
        <p className="genres__message" role="alert">
          {message(pages.error)}{" "}
          <button
            type="button"
            className="text-link"
            onClick={pages.loadMore}
            disabled={pages.loadingMore}
          >
            {words.loadMore}
          </button>
        </p>
      ) : pages.next !== null ? (
        <p className="genres__more" role="status">
          {words.loading}
        </p>
      ) : null}
    </>
  );
}

function GenreRow({
  api,
  node,
  handlers,
  hover,
  parent,
  index,
}: {
  api: Api;
  node: GenreNode;
  handlers: GenreHandlers;
  hover: HoverHandlers;
  parent: GenreNode | null;
  index: number;
}) {
  const [expanded, setExpanded] = useState(false);
  const words = useCopy().genres;
  const name = genreDisplayName(node.genre_id, node.name);
  const liked = handlers.liked.has(node.genre_id);
  const disliked = handlers.disliked.has(node.genre_id);
  // Notes follow a mouse or the keyboard; a finger tap selects without a card in the way.
  const anchorOf = (button: HTMLElement): Anchor => {
    const label = button.querySelector(".tree__label") ?? button;
    const rect = label.getBoundingClientRect();
    return {
      left: rect.left,
      right: rect.right,
      top: rect.top,
      bottom: rect.bottom,
    };
  };
  const onEnter = (event: ReactPointerEvent<HTMLElement>) => {
    if (event.pointerType === "touch") return;
    hover.show(node, parent, anchorOf(event.currentTarget));
  };
  const onFocus = (event: ReactFocusEvent<HTMLElement>) => {
    if (!event.currentTarget.matches(":focus-visible")) return;
    hover.show(node, parent, anchorOf(event.currentTarget));
  };
  return (
    <li
      className="tree__item"
      style={{ "--i": Math.min(index, 12) } as CSSProperties}
    >
      <div
        className="tree__row"
        data-state={liked ? "liked" : disliked ? "disliked" : undefined}
      >
        {node.has_children ? (
          <button
            type="button"
            className="tree__toggle"
            aria-expanded={expanded}
            aria-label={expanded ? words.collapse(name) : words.expand(name)}
            onClick={() => setExpanded((value) => !value)}
          >
            <Icon name="chevronRight" size={15} />
          </button>
        ) : (
          <span
            className="tree__toggle tree__toggle--leaf"
            aria-hidden="true"
          />
        )}
        <button
          type="button"
          className="tree__name"
          disabled={handlers.locked}
          onPointerEnter={onEnter}
          onPointerLeave={hover.hide}
          onFocus={onFocus}
          onBlur={hover.hide}
          onClick={() => {
            if (handlers.onPick(node) && node.has_children) setExpanded(true);
          }}
        >
          <span className="tree__label">{name}</span>
        </button>
        <div className="tree__actions">
          <button
            type="button"
            className="tree__pill"
            aria-pressed={liked}
            aria-label={words.likeName(name)}
            title={words.like}
            disabled={handlers.locked}
            onClick={() => handlers.onToggleLike(node)}
          >
            <Icon name="heart" size={14} filled={liked} />
          </button>
          <button
            type="button"
            className="tree__pill tree__pill--avoid"
            aria-pressed={disliked}
            aria-label={words.dislikeName(name)}
            title={words.dislike}
            disabled={handlers.locked}
            onClick={() => handlers.onToggleDislike(node)}
          >
            <Icon name="ban" size={14} />
          </button>
        </div>
      </div>
      {expanded ? (
        <Children api={api} parent={node} handlers={handlers} hover={hover} />
      ) : null}
    </li>
  );
}

function Children({
  api,
  parent,
  handlers,
  hover,
}: {
  api: Api;
  parent: GenreNode;
  handlers: GenreHandlers;
  hover: HoverHandlers;
}) {
  const pages = useGenrePages(api, `p:${parent.genre_id}`, "", parent.genre_id);
  const words = useCopy().genres;
  useLoadAll(pages);
  return (
    <div className="branch" data-ready={pages.status === "ready" || undefined}>
      <GenreList
        api={api}
        pages={pages}
        handlers={handlers}
        hover={hover}
        parent={parent}
        empty={words.noChildren}
      />
    </div>
  );
}
