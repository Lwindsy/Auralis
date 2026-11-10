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
import type { GenreNode, GenreSearchResult } from "../../api/types.ts";
import { genreNote } from "../../content/genreNotes.ts";
import { useDebounced } from "../../state/hooks.ts";
import { labels } from "../../state/labels.ts";
import { Icon } from "../Icon.tsx";
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

interface Hover {
  node: GenreNode;
  parent: GenreNode | null;
  x: number;
  y: number;
}

type HoverHandlers = {
  show: (
    node: GenreNode,
    parent: GenreNode | null,
    at: { x: number; y: number },
  ) => void;
  hide: () => void;
};

function relation(node: GenreNode, parent: GenreNode | null) {
  if (parent) return `「${parent.name}」的子风格`;
  const parents = node.parent_ids
    .map((id) => labels.genre(id)?.name)
    .filter(Boolean);
  if (parents.length) return `属于「${parents.join("」「")}」`;
  return node.has_children ? "风格家族 · 点开看子风格" : "独立风格";
}

/**
 * Genre browser: families first, stand-alone genres gathered in Unique. Hovering a name
 * opens a small card with a short note; opening a family lets its sub-genres grow out of
 * the parent along a drawn branch, in a list that scrolls on its own.
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
  const debounced = useDebounced(query.trim(), 220);
  const effective = immediate ?? debounced;
  const pages = useGenrePages(api, `q:${effective}`, effective, null);
  const { status, next, loadingMore, error, loadMore } = pages;
  useEffect(() => {
    if (
      !effective &&
      status === "ready" &&
      next !== null &&
      !loadingMore &&
      !error
    )
      loadMore();
  }, [effective, status, next, loadingMore, error, loadMore]);

  const [hover, setHover] = useState<Hover | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const hoverHandlers: HoverHandlers = {
    show: (node, parent, at) => {
      clearTimeout(timer.current);
      timer.current = setTimeout(() => setHover({ node, parent, ...at }), 260);
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
        label="查找风格"
        placeholder="搜索风格，如 jazz、shoegaze、爵士"
        value={query}
        onChange={(value) => {
          setQuery(value);
          setImmediate(null);
        }}
        onSubmit={() => setImmediate(query.trim())}
      />
      <div
        className="genres__list"
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
            empty="没有匹配的风格。"
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
      {hover ? <HoverCard hover={hover} key={hover.node.genre_id} /> : null}
    </div>
  );
}

function HoverCard({ hover }: { hover: Hover }) {
  const note = genreNote(hover.node.name);
  const width = 272;
  const left = Math.min(hover.x + 18, window.innerWidth - width - 16);
  const top = Math.min(hover.y + 14, window.innerHeight - 180);
  // Portalled: the doors use backdrop blur, which would trap a fixed element inside.
  return createPortal(
    <div
      className="genre-card"
      role="tooltip"
      style={{ left, top, width } as CSSProperties}
    >
      <p className="genre-card__name">{hover.node.name}</p>
      {note ? <p className="genre-card__note">{note}</p> : null}
      <p className="genre-card__relation mono">
        {relation(hover.node, hover.parent)}
      </p>
    </div>,
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
  if (pages.status !== "ready" || !pages.items.length)
    return (
      <GenreList
        api={api}
        pages={pages}
        handlers={handlers}
        hover={hover}
        parent={null}
        empty="暂时没有可选的风格。"
      />
    );
  const families = pages.items.filter((node) => node.has_children);
  const loners = pages.items.filter((node) => !node.has_children);
  const complete = pages.next === null;
  return (
    <>
      <ul className="tree" role="list" aria-label="风格家族">
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
                aria-label={`${uniqueOpen ? "收起" : "展开"}${UNIQUE_GROUP}`}
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
                <p className="branch__note">
                  目录里没有上下级关系的风格，Unique 只是归类，不是一种风格。
                </p>
                <ul className="tree" role="list" aria-label="独立风格">
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
          {pages.error}{" "}
          <button type="button" className="text-link" onClick={pages.loadMore}>
            继续加载
          </button>
        </p>
      ) : !complete ? (
        <p className="genres__message" role="status">
          正在载入全部风格…
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
  if (pages.status === "loading" || pages.status === "idle")
    return (
      <div className="genres__skeleton" role="status" aria-label="正在查询…">
        {Array.from({ length: parent ? 3 : 6 }, (_, i) => (
          <span key={i} style={{ width: `${70 - i * 8}%` }} />
        ))}
      </div>
    );
  if (pages.status === "error")
    return (
      <p className="genres__message" role="alert">
        {pages.error}{" "}
        <button type="button" className="text-link" onClick={pages.retry}>
          重试
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
      {pages.next !== null ? (
        <button
          type="button"
          className="genres__more"
          onClick={pages.loadMore}
          disabled={pages.loadingMore}
        >
          {pages.loadingMore ? "正在加载…" : "更多"}
        </button>
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
  const liked = handlers.liked.has(node.genre_id);
  const disliked = handlers.disliked.has(node.genre_id);
  // Notes follow a mouse or the keyboard; a finger tap selects without a card in the way.
  const onEnter = (event: ReactPointerEvent<HTMLElement>) => {
    if (event.pointerType === "touch") return;
    hover.show(node, parent, { x: event.clientX, y: event.clientY });
  };
  const onFocus = (event: ReactFocusEvent<HTMLElement>) => {
    if (!event.currentTarget.matches(":focus-visible")) return;
    const rect = event.currentTarget.getBoundingClientRect();
    hover.show(node, parent, { x: rect.left + 40, y: rect.bottom });
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
            aria-label={`${expanded ? "收起" : "展开"}${node.name}`}
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
          {node.name}
        </button>
        <div className="tree__actions">
          <button
            type="button"
            className="tree__pill"
            aria-pressed={liked}
            aria-label={`喜欢${node.name}`}
            title="喜欢"
            disabled={handlers.locked}
            onClick={() => handlers.onToggleLike(node)}
          >
            <Icon name="heart" size={14} filled={liked} />
          </button>
          <button
            type="button"
            className="tree__pill tree__pill--avoid"
            aria-pressed={disliked}
            aria-label={`不喜欢${node.name}`}
            title="不喜欢"
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
  return (
    <div className="branch" data-ready={pages.status === "ready" || undefined}>
      <GenreList
        api={api}
        pages={pages}
        handlers={handlers}
        hover={hover}
        parent={parent}
        empty="没有子风格。"
      />
    </div>
  );
}
