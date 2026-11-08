package io.auralis.catalog.api;

import java.util.*;

/** Version-pinned batch identity lookup for both seeds and candidates. */
public interface CatalogQuery {
    record Genre(String genreId, String name) {}

    record Album(
            UUID id,
            String title,
            String artistCredit,
            Integer year,
            String primaryType,
            List<String> secondaryTypes,
            List<Genre> genres) {
        public Album {
            secondaryTypes = List.copyOf(secondaryTypes);
            genres = List.copyOf(genres);
        }
    }

    Map<UUID, Album> albums(long dataset, List<UUID> requested);
}
