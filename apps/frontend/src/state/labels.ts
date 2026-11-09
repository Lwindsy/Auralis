import type { Api } from "../api/client.ts";
import type { CatalogAlbum, GenreNode, ResolvedAlbum } from "../api/types.ts";

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

/**
 * In-memory display names for catalog IDs seen in this page. Nothing is persisted: the
 * page never stores input in browser storage, so names are re-read from the catalog.
 */
class LabelStore {
  private labels = new Map<string, Label>();
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

  rememberGenres(nodes: GenreNode[]) {
    let changed = false;
    for (const node of nodes) {
      if (this.genre(node.genre_id)?.name === node.name) continue;
      this.labels.set(node.genre_id, { kind: "genre", name: node.name });
      changed = true;
    }
    if (changed) this.emit();
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
