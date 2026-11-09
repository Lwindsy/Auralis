import { useCallback, useState } from "react";
import type { Api } from "../../api/client.ts";
import type { AlbumSearchResult, CatalogAlbum } from "../../api/types.ts";
import { useDebounced } from "../../state/hooks.ts";
import { MAX_ALBUMS } from "../../state/input.ts";
import { labels } from "../../state/labels.ts";
import { albumSeed } from "../../lib/cover.ts";
import { Cover } from "../Cover.tsx";
import { Icon } from "../Icon.tsx";
import { SearchField } from "./SearchField.tsx";
import { usePaged } from "./usePaged.ts";

export function AlbumSearch({
  api,
  selected,
  locked,
  onAdd,
}: {
  api: Api;
  selected: ReadonlySet<string>;
  locked: boolean;
  onAdd: (album: CatalogAlbum) => void;
}) {
  const [query, setQuery] = useState("");
  const [immediate, setImmediate] = useState<string | null>(null);
  const debounced = useDebounced(query.trim(), 380);
  const effective = immediate ?? debounced;
  const load = useCallback(
    async (offset: number, signal: AbortSignal) => {
      const page = await api.request<AlbumSearchResult>(
        "GET",
        "/v1/catalog/albums",
        {
          query: { query: effective, offset: String(offset), limit: "20" },
          signal,
        },
      );
      labels.rememberAlbums(page.items);
      return page;
    },
    [api, effective],
  );
  const pages = usePaged<CatalogAlbum>(
    effective ? `a:${effective}` : null,
    load,
  );
  const full = selected.size >= MAX_ALBUMS;
  return (
    <div className="browser">
      <SearchField
        id="albums-query"
        label="查找专辑"
        placeholder="搜索艺人或专辑名"
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
        {full ? (
          <p className="browser__message browser__message--note">
            最多选择 {MAX_ALBUMS} 张专辑。
          </p>
        ) : null}
        {!effective ? (
          <div className="browser__hint">
            <Icon name="disc" size={22} />
            <span>输入艺人或专辑名称开始搜索，最多添加 {MAX_ALBUMS} 张。</span>
          </div>
        ) : pages.status === "loading" || pages.status === "idle" ? (
          <div
            className="browser__skeleton"
            role="status"
            aria-label="正在查询…"
          >
            {Array.from({ length: 4 }, (_, i) => (
              <span key={i} className="skeleton-album" />
            ))}
          </div>
        ) : pages.status === "error" ? (
          <div className="browser__message" role="alert">
            <span>{pages.error}</span>
            <button type="button" className="link-button" onClick={pages.retry}>
              重试搜索
            </button>
          </div>
        ) : !pages.items.length ? (
          <p className="browser__message">没有匹配结果。</p>
        ) : (
          <>
            <ul className="album-results" role="list">
              {pages.items.map((album) => {
                const id = album.mb_release_group_id;
                const {
                  title,
                  artist_credit,
                  first_release_year,
                  release_type,
                } = album.identity;
                const name = `${title} — ${artist_credit}`;
                const added = selected.has(id);
                return (
                  <li key={id} className="album-result">
                    <Cover
                      seed={albumSeed(title, artist_credit)}
                      title={title}
                      size="mini"
                    />
                    <div className="album-result__text">
                      <span className="album-result__title">{title}</span>
                      <span className="album-result__meta">
                        {artist_credit}
                        {" · "}
                        {first_release_year ?? "年份未知"}
                        {release_type ? ` · ${release_type}` : ""}
                      </span>
                    </div>
                    <button
                      type="button"
                      className="pill-toggle pill-toggle--add"
                      aria-label={added ? `已添加${name}` : `添加${name}`}
                      aria-pressed={added}
                      disabled={locked || added || full}
                      onClick={() => onAdd(album)}
                    >
                      <Icon name={added ? "check" : "plus"} size={14} />
                      <span aria-hidden="true">
                        {added ? "已添加" : "添加"}
                      </span>
                    </button>
                  </li>
                );
              })}
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
        )}
      </div>
    </div>
  );
}
