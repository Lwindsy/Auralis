package io.auralis.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import io.auralis.catalog.api.AlbumCovers;
import io.auralis.catalog.application.CachedAlbumCovers;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CoverArtArchiveTest {
    static final UUID ID = UUID.fromString("c31a5e2b-0bf8-32e0-8aeb-ef4ba9973932");
    static final byte[] PNG =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ioAAAAASUVORK5CYII=");

    @Test
    void redirectsImagesButRejectsForeignTargetsAndSeparatesAbsenceFromFailure() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new AtomicInteger(307);
        server.createContext(
                "/release-group/",
                exchange -> {
                    int code = status.get();
                    exchange.getResponseHeaders()
                            .set("Location", code == 302 ? "http://localhost:1/private" : "/image");
                    exchange.sendResponseHeaders(code, -1);
                    exchange.close();
                });
        server.createContext(
                "/image",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.sendResponseHeaders(200, PNG.length);
                    exchange.getResponseBody().write(PNG);
                    exchange.close();
                });
        server.start();
        try {
            var source =
                    new CoverArtArchive(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            var result = source.fetch(ID, 500);
            assertEquals(200, result.status());
            assertArrayEquals(PNG, result.bytes());
            status.set(404);
            assertEquals(404, source.fetch(ID, 500).status());
            status.set(503);
            assertEquals(503, source.fetch(ID, 500).status());
            status.set(302);
            assertEquals(503, source.fetch(ID, 500).status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void coalescesConcurrentRequestsAndRetriesTransientFailureAfterExpiry() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong();
        Clock clock =
                new Clock() {
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    public Instant instant() {
                        return Instant.ofEpochMilli(now.get());
                    }
                };
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var cache =
                new CachedAlbumCovers(
                        (id, size) -> {
                            int n = calls.incrementAndGet();
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return n == 1
                                    ? AlbumCovers.Image.unavailable()
                                    : new AlbumCovers.Image(200, "image/png", PNG);
                        },
                        clock)) {
            var first = cache.get(ID, 500);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertSame(first, cache.get(ID, 500));
            release.countDown();
            assertEquals(503, first.get(5, TimeUnit.SECONDS).status());
            assertEquals(503, cache.get(ID, 500).get().status());
            assertEquals(1, calls.get());
            now.set(15001);
            assertEquals(200, cache.get(ID, 500).get(5, TimeUnit.SECONDS).status());
            assertEquals(200, cache.get(ID, 500).get().status());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void exposesImageBytesThroughAsyncHttpWithIndependentCachePolicy() throws Exception {
        try (var cache =
                new CachedAlbumCovers(
                        (id, size) -> new AlbumCovers.Image(200, "image/png", PNG),
                        Clock.systemUTC())) {
            var mvc = MockMvcBuilders.standaloneSetup(new CoverHttpController(cache)).build();
            var pending =
                    mvc.perform(get(AlbumCovers.url(ID, 250)))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            mvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("image/png"))
                    .andExpect(content().bytes(PNG))
                    .andExpect(header().string("Cache-Control", "private, max-age=86400"));
            var bad = mvc.perform(get("/v1/catalog/albums/invalid/cover")).andReturn();
            mvc.perform(asyncDispatch(bad)).andExpect(status().isBadRequest());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "auralis.cover.live", matches = "true")
    void fetchesActualCaaImage() throws Exception {
        var result = new CoverArtArchive().fetch(ID, 250);
        assertEquals(200, result.status());
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(result.bytes()));
        assertNotNull(image);
        System.out.println(
                "CAA_REAL_IMAGE "
                        + result.contentType()
                        + " "
                        + image.getWidth()
                        + "x"
                        + image.getHeight());
    }
}
