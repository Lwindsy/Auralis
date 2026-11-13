package io.auralisy.bootstrap;

import io.auralisy.catalog.api.AlbumCovers;
import io.auralisy.catalog.application.CachedAlbumCovers;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public final class CoverHttpController {
    private final AlbumCovers covers;

    public CoverHttpController(AlbumCovers covers) {
        this.covers = covers;
    }

    @GetMapping("/v1/catalog/albums/{mbid}/cover")
    public CompletableFuture<ResponseEntity<byte[]>> cover(
            @PathVariable String mbid, @RequestParam(defaultValue = "500") int size) {
        UUID album;
        try {
            album = UUID.fromString(mbid);
            if (!album.toString().equalsIgnoreCase(mbid)) throw new IllegalArgumentException();
            AlbumCovers.url(album, size);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.badRequest().header("Cache-Control", "no-store").build());
        }
        return covers.get(album, size)
                .thenApply(
                        image -> {
                            int ttl =
                                    image.status() == 200
                                            ? 86400
                                            : image.status() == 404 ? 3600 : 15;
                            var response =
                                    ResponseEntity.status(image.status())
                                            .header("Cache-Control", "private, max-age=" + ttl);
                            if (image.status() == 200)
                                response.header("Content-Type", image.contentType());
                            if (image.status() == 503) response.header("Retry-After", "15");
                            return response.body(image.bytes());
                        });
    }

    @Configuration
    static class ConfigurationBeans {
        @Bean(destroyMethod = "close")
        CachedAlbumCovers albumCovers() {
            return new CachedAlbumCovers(new CoverArtArchive(), Clock.systemUTC());
        }
    }
}
