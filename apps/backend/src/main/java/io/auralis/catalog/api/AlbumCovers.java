package io.auralis.catalog.api;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Public artwork only; no recommendation or personal state is read or changed. */
public interface AlbumCovers {
    record Image(int status, String contentType, byte[] bytes) {
        public static Image missing() {
            return new Image(404, "", new byte[0]);
        }

        public static Image unavailable() {
            return new Image(503, "", new byte[0]);
        }
    }

    CompletableFuture<Image> get(UUID album, int size);

    static String url(UUID album, int size) {
        if (!Set.of(250, 500, 1200).contains(size))
            throw new IllegalArgumentException("INVALID_COVER_SIZE");
        return "/v1/catalog/albums/" + album + "/cover?size=" + size;
    }
}
