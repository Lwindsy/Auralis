import { useCallback, useState, type CSSProperties } from "react";
import type { Api } from "../../api/client.ts";
import type { GenreNode, GenreSearchResult } from "../../api/types.ts";
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

export function GenreBrowser({
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
  return (
    <div className="browser">
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
        className="browser__list"
        aria-live="polite"
        aria-busy={pages.status === "loading"}
      >
        <GenreList
          api={api}
          pages={pages}
          handlers={handlers}
          depth={0}
          empty={effective ? "没有匹配的风格。" : "暂时没有可选的风格。"}
        />
      </div>
    </div>
  );
}

function GenreList({
  api,
  pages,
  handlers,
  depth,
  empty,
}: {
  api: Api;
  pages: ReturnType<typeof useGenrePages>;
  handlers: GenreHandlers;
  depth: number;
  empty: string;
}) {
  if (pages.status === "loading" || pages.status === "idle")
    return (
      <div className="browser__skeleton" aria-label="正在查询…" role="status">
        {Array.from({ length: depth ? 2 : 5 }, (_, i) => (
          <span
            key={i}
            className="skeleton-line"
            style={{ width: `${68 - i * 9}%` }}
          />
        ))}
      </div>
    );
  if (pages.status === "error")
    return (
      <div className="browser__message" role="alert">
        <span>{pages.error}</span>
        <button type="button" className="link-button" onClick={pages.retry}>
          重试
        </button>
      </div>
    );
  if (!pages.items.length) return <p className="browser__message">{empty}</p>;
  return (
    <>
      <ul className="tree" role="list">
        {pages.items.map((node) => (
          <GenreRow
            key={node.genre_id}
            api={api}
            node={node}
            handlers={handlers}
            depth={depth}
          />
        ))}
      </ul>
      {pages.error ? (
        <p className="browser__message" role="alert">
          {pages.error}
        </p>
      ) : null}
      {pages.next !== null ? (
        <button
          type="button"
          className="browser__more"
          onClick={pages.loadMore}
          disabled={pages.loadingMore}
        >
          {pages.loadingMore ? "正在加载…" : "加载更多"}
        </button>
      ) : null}
    </>
  );
}

function GenreRow({
  api,
  node,
  handlers,
  depth,
}: {
  api: Api;
  node: GenreNode;
  handlers: GenreHandlers;
  depth: number;
}) {
  const [expanded, setExpanded] = useState(false);
  const liked = handlers.liked.has(node.genre_id);
  const disliked = handlers.disliked.has(node.genre_id);
  return (
    <li className="tree__item">
      <div
        className="tree__row"
        data-state={liked ? "liked" : disliked ? "disliked" : undefined}
        style={{ "--depth": depth } as CSSProperties}
      >
        {node.has_children ? (
          <button
            type="button"
            className="tree__toggle"
            aria-expanded={expanded}
            aria-label={`${expanded ? "收起" : "展开"}${node.name}`}
            onClick={() => setExpanded((value) => !value)}
          >
            <Icon name="chevronRight" size={16} />
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
          onClick={() => {
            if (handlers.onPick(node) && node.has_children) setExpanded(true);
          }}
        >
          {node.name}
        </button>
        <div className="tree__actions">
          <button
            type="button"
            className="pill-toggle pill-toggle--like"
            aria-pressed={liked}
            aria-label={`喜欢${node.name}`}
            disabled={handlers.locked}
            onClick={() => handlers.onToggleLike(node)}
          >
            <Icon name="heart" size={14} filled={liked} />
            <span aria-hidden="true">喜欢</span>
          </button>
          <button
            type="button"
            className="pill-toggle pill-toggle--dislike"
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
        <GenreChildren
          api={api}
          parent={node}
          handlers={handlers}
          depth={depth + 1}
        />
      ) : null}
    </li>
  );
}

function GenreChildren({
  api,
  parent,
  handlers,
  depth,
}: {
  api: Api;
  parent: GenreNode;
  handlers: GenreHandlers;
  depth: number;
}) {
  const pages = useGenrePages(api, `p:${parent.genre_id}`, "", parent.genre_id);
  return (
    <div
      className="tree__children"
      style={{ "--depth": depth } as CSSProperties}
    >
      <GenreList
        api={api}
        pages={pages}
        handlers={handlers}
        depth={depth}
        empty="没有子风格。"
      />
    </div>
  );
}
