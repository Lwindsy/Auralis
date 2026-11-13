package io.auralisy.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.auralisy.bootstrap.*;
import io.auralisy.catalog.adapters.CatalogIndexBuild;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "AURALIS_SEARCH_TEST_POSTGRES_URL", matches = ".+")
class CatalogSearchIT {
    @TempDir Path temp;

    @Test
    void actualHttpCookieAndSchemaWithRealIndexAndPostgres() throws Exception {
        LuceneCatalogSearchTest.fixture(temp.resolve("source"));
        var album =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        new ObjectMapper()
                                .readTree(Files.readString(temp.resolve("source/albums.jsonl")));
        var single =
                album.deepCopy()
                        .put("id", "33333333-3333-4333-8333-333333333333")
                        .put("title", "GateSingle")
                        .put("type", "Single");
        single.putArray("aliases");
        single.putArray("redirects");
        String digest =
                LuceneCatalogSearchTest.writeRows(
                        temp.resolve("source"), java.util.List.of(album, single));
        CatalogIndexBuild.build(temp.resolve("source"), temp.resolve("index"), digest);
        try (var host = new CatalogSearchHarness(temp.resolve("index"), digest);
                var anonymous = HttpClient.newHttpClient();
                var client =
                        HttpClient.newBuilder()
                                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                                .build()) {
            var json = new ObjectMapper();
            var schema = host.context.getBean(WireProtocol.class);
            var unauthorized =
                    anonymous.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    host.base + "/v1/catalog/albums?query=debut"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(400, unauthorized.statusCode());
            assertTrue(schema.validProductError(json.readTree(unauthorized.body())));
            var bootstrap =
                    client.send(
                            HttpRequest.newBuilder(URI.create(host.base + "/v1/bootstrap"))
                                    .header("Origin", host.base)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, bootstrap.statusCode(), bootstrap.body());
            String coverPath =
                    "/v1/catalog/albums/c31a5e2b-0bf8-32e0-8aeb-ef4ba9973932/cover?size=250";
            assertEquals(
                    400,
                    anonymous
                            .send(
                                    HttpRequest.newBuilder(URI.create(host.base + coverPath))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(
                    400,
                    client.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            host.base
                                                                    + coverPath.replace(
                                                                            "250", "999")))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            if (Boolean.getBoolean("auralis.cover.live")) {
                var cover =
                        client.send(
                                HttpRequest.newBuilder(URI.create(host.base + coverPath))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, cover.statusCode());
                assertEquals(
                        "private, max-age=86400",
                        cover.headers().firstValue("Cache-Control").orElseThrow());
                assertNotNull(
                        javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(cover.body())));
                assertTrue(
                        cover.headers().firstValue("Set-Cookie").isEmpty(),
                        "artwork does not renew identity");
            }
            for (var pair :
                    new String[][] {
                        {"/v1/catalog/albums?query=Bjork", "AlbumSearchResult"},
                        {"/v1/catalog/albums/" + LuceneCatalogSearchTest.OLD, "ResolvedAlbum"},
                        {"/v1/catalog/genres?query=%E6%91%87%E6%BB%9A", "GenreSearchResult"}
                    }) {
                var response =
                        client.send(
                                HttpRequest.newBuilder(URI.create(host.base + pair[0]))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(
                        schema.validDefinition(pair[1], json.readTree(response.body())),
                        response.body());
                assertTrue(
                        response.headers().firstValue("Set-Cookie").isEmpty(),
                        "read must not renew");
                assertEquals(
                        "no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            }
            for (String query : new String[] {"Bjork", "GateSingle"}) {
                var response =
                        client.send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        host.base
                                                                + "/v1/catalog/albums?query="
                                                                + query))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var page = json.readTree(response.body());
                assertEquals(query.equals("GateSingle") ? 0 : 1, page.path("items").size());
                for (var item : page.path("items"))
                    assertNotEquals("Single", item.path("identity").path("release_type").asText());
                assertTrue(page.path("next_offset").isNull());
            }
            for (String path :
                    new String[] {
                        "/v1/catalog/albums?query=x&limit=51",
                        "/v1/catalog/albums?query=x&offset=oops",
                        "/v1/catalog/albums?query=%20",
                        "/v1/catalog/albums/not-a-uuid"
                    }) {
                var response =
                        client.send(
                                HttpRequest.newBuilder(URI.create(host.base + path)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode(), path);
                assertTrue(schema.validProductError(json.readTree(response.body())));
            }
            var missing =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    host.base
                                                            + "/v1/catalog/albums/99999999-9999-4999-8999-999999999999"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
            var empty =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    host.base
                                                            + "/v1/catalog/albums?query=notfound"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, empty.statusCode());
            assertTrue(json.readTree(empty.body()).path("items").isEmpty());
        }
    }
}
