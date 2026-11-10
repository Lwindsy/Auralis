import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
} from "react";
import type { Api } from "../../api/client.ts";
import type { AlbumSearchResult, CatalogAlbum } from "../../api/types.ts";
import { albumSeed } from "../../lib/cover.ts";
import { useDebounced } from "../../state/hooks.ts";
import { MAX_ALBUMS } from "../../state/input.ts";
import { labels } from "../../state/labels.ts";
import { Cover } from "../Cover.tsx";
import { Icon } from "../Icon.tsx";
import { QuietScroll } from "../QuietScroll.tsx";
import { SearchField } from "./SearchField.tsx";
import { usePaged } from "./usePaged.ts";

/**
 * Album search as a crate of sleeves: a grid four across that scrolls downwards, like a
 * streaming library. Each sleeve shows the title, the artist and the year in separate
 * lines, all in full. Sleeves lift and catch light under the pointer; adding one stamps it
 * with a check. The next page loads before the end of the list comes into view.
 */
export function AlbumShelf({
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
  const shelf = useRef<HTMLUListElement>(null);
  const sentinel = useRef<HTMLLIElement>(null);
  const { next, loadingMore, loadMore, status } = pages;

  useEffect(() => {
    const target = sentinel.current;
    if (!target || next === null) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting) && !loadingMore)
          loadMore();
      },
      { root: shelf.current, rootMargin: "0px 0px 480px 0px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [next, loadingMore, loadMore, status]);

  return (
    <div className="albums">
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
        className="albums__stage"
        aria-live="polite"
        aria-busy={pages.status === "loading"}
      >
        {full ? (
          <p className="albums__message">最多选择 {MAX_ALBUMS} 张专辑。</p>
        ) : null}
        {!effective ? (
          <p className="albums__message albums__message--hint">
            输入艺人或专辑名，结果会排成一墙唱片。
          </p>
        ) : pages.status === "loading" || pages.status === "idle" ? (
          <ul
            className="shelf shelf--loading"
            aria-label="正在查询…"
            role="status"
          >
            {Array.from({ length: 8 }, (_, i) => (
              <li
                key={i}
                className="sleeve sleeve--ghost"
                style={{ "--i": i } as CSSProperties}
              />
            ))}
          </ul>
        ) : pages.status === "error" ? (
          <p className="albums__message" role="alert">
            {pages.error}{" "}
            <button type="button" className="text-link" onClick={pages.retry}>
              重试搜索
            </button>
          </p>
        ) : !pages.items.length ? (
          <p className="albums__message">没有匹配结果。</p>
        ) : (
          <QuietScroll className="shelf-frame">
            <ul
              ref={shelf}
              className="shelf quiet-scroll"
              role="list"
              aria-label="专辑搜索结果"
            >
              {pages.items.map((album, index) => {
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
                  <li
                    key={id}
                    className="sleeve"
                    data-added={added || undefined}
                    style={{ "--i": index % 20 } as CSSProperties}
                  >
                    <button
                      type="button"
                      className="sleeve__button"
                      aria-label={added ? `已添加${name}` : `添加${name}`}
                      aria-pressed={added}
                      disabled={locked || added || full}
                      onClick={() => onAdd(album)}
                    >
                      <span className="sleeve__art">
                        <Cover
                          seed={albumSeed(title, artist_credit)}
                          title={title}
                        />
                        <span className="sleeve__stamp" aria-hidden="true">
                          <Icon name={added ? "check" : "plus"} size={16} />
                        </span>
                      </span>
                      <span className="sleeve__title">{title}</span>
                      <span className="sleeve__artist">{artist_credit}</span>
                      <span className="sleeve__year mono">
                        {first_release_year ?? "年份未知"}
                        {release_type ? ` · ${release_type}` : ""}
                      </span>
                    </button>
                  </li>
                );
              })}
              {next !== null
                ? Array.from({ length: 4 }, (_, i) => (
                    <li
                      key={`more-${i}`}
                      ref={i === 0 ? sentinel : undefined}
                      className="sleeve sleeve--ghost"
                      aria-hidden="true"
                      style={{ "--i": i } as CSSProperties}
                    />
                  ))
                : null}
            </ul>
          </QuietScroll>
        )}
      </div>
    </div>
  );
}
