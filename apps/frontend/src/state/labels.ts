import type { Api } from "../api/client.ts";
import type {
  CatalogAlbum,
  GenreNode,
  GenreSearchResult,
  ResolvedAlbum,
} from "../api/types.ts";

export interface GenreLabel {
  kind: "genre";
  name: string;
}
export interface AlbumLabel {
  kind: "album";
  title: string;
  artist: string;
  year: number | null;
}
export type Label = GenreLabel | AlbumLabel;

/** Catalog genre pages read per request, and the most requests one name lookup may use. */
const GENRE_PAGE = 50;
const GENRE_LOOKUP_BUDGET = 240;

/**
 * In-memory display names for catalog IDs seen in this page. Nothing is persisted: the
 * page never stores input in browser storage, so names are re-read from the catalog.
 * Albums can be read by ID. Genres cannot, so after a reload the names of picked genres
 * are found by walking the genre tree from its roots until every one has turned up.
 */
class LabelStore {
  private labels = new Map<string, Label>();
  private walking = false;
  private inflight = new Set<string>();
  private listeners = new Set<() => void>();
  version = 0;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getVersion = () => this.version;

  private emit() {
    this.version++;
    for (const listener of this.listeners) listener();
  }

  genre(id: string): GenreLabel | null {
    const label = this.labels.get(id);
    return label?.kind === "genre" ? label : null;
  }

  album(id: string): AlbumLabel | null {
    const label = this.labels.get(id);
    return label?.kind === "album" ? label : null;
  }

  rememberGenres(nodes: readonly Pick<GenreNode, "genre_id" | "name">[]) {
    let changed = false;
    for (const node of nodes) {
      if (this.genre(node.genre_id)?.name === node.name) continue;
      this.labels.set(node.genre_id, { kind: "genre", name: node.name });
      changed = true;
    }
    if (changed) this.emit();
  }

  /**
   * Finds names for picked genres the page has not seen (after a reload). Walks the tree
   * breadth-first, a few families at a time, and stops as soon as all are known or the
   * request budget is spent; chips show a neutral label meanwhile.
   */
  async resolveGenres(api: Api, ids: string[]) {
    const missing = new Set(ids.filter((id) => !this.genre(id)));
    if (!missing.size || this.walking) return;
    this.walking = true;
    let requests = 0;
    const listAll = async (parent: string | null) => {
      const nodes: GenreNode[] = [];
      let offset: number | null = 0;
      while (offset !== null && requests < GENRE_LOOKUP_BUDGET) {
        requests++;
        const page: GenreSearchResult = await api.request<GenreSearchResult>(
          "GET",
          "/v1/catalog/genres",
          {
            query: {
              ...(parent ? { parent_id: parent } : {}),
              offset: String(offset),
              limit: String(GENRE_PAGE),
            },
          },
        );
        nodes.push(...page.items);
        offset = page.next_offset;
      }
      return nodes;
    };
    try {
      const queue: (string | null)[] = [null];
      const visited = new Set<string>();
      while (queue.length && missing.size && requests < GENRE_LOOKUP_BUDGET) {
        const batch = queue.splice(0, 6);
        const found = (await Promise.all(batch.map(listAll))).flat();
        this.rememberGenres(found);
        for (const node of found) {
          missing.delete(node.genre_id);
          if (node.has_children && !visited.has(node.genre_id)) {
            visited.add(node.genre_id);
            queue.push(node.genre_id);
          }
        }
      }
    } catch {
      // The catalog is unavailable; selections stay as they are under a neutral label.
    } finally {
      this.walking = false;
    }
  }

  rememberAlbums(albums: CatalogAlbum[]) {
    let changed = false;
    for (const album of albums) {
      if (this.album(album.mb_release_group_id)) continue;
      this.labels.set(album.mb_release_group_id, {
        kind: "album",
        title: album.identity.title,
        artist: album.identity.artist_credit,
        year: album.identity.first_release_year,
      });
      changed = true;
    }
    if (changed) this.emit();
  }

  /** Re-reads names for previously selected albums; selections survive catalog failures. */
  async resolveAlbums(api: Api, ids: string[]) {
    const missing = ids.filter(
      (id) => !this.album(id) && !this.inflight.has(id),
    );
    await Promise.all(
      missing.map(async (id) => {
        this.inflight.add(id);
        try {
          const value = await api.request<ResolvedAlbum>(
            "GET",
            "/v1/catalog/albums/{mb_release_group_id}",
            { params: { mb_release_group_id: id } },
          );
          this.rememberAlbums([value.album]);
        } catch {
          // Keep the original selection and marks even if the catalog is unavailable.
        } finally {
          this.inflight.delete(id);
        }
      }),
    );
  }
}

export const labels = new LabelStore();

export const albumName = (label: AlbumLabel) =>
  `${label.title} — ${label.artist}`;
