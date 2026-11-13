package io.auralisy.catalog.api;

import java.util.List;
import java.util.UUID;

/** Public external directory. Results are public facts, never user preferences. */
public interface CatalogSearchPort {
    record Album(
            UUID id,
            String title,
            String artist,
            Integer year,
            String type,
            List<CatalogQuery.Genre> genres,
            int score) {
        public Album {
            genres = List.copyOf(genres);
        }
    }

    record Page(List<Album> albums, Integer nextOffset) {
        public Page {
            albums = List.copyOf(albums);
        }
    }

    Page search(String query, int offset, int limit);

    Album resolve(UUID id);

    String revision();
}
