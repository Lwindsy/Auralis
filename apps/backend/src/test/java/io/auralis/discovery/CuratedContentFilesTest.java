package io.auralis.discovery;

import static io.auralis.contracts.CanonicalJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

import io.auralis.discovery.adapters.CuratedContentFiles;
import io.auralis.discovery.api.CuratedContent;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CuratedContentFilesTest {
    @TempDir Path temp;

    static CuratedContent content() {
        var albums = new ArrayList<CuratedContent.Album>();
        for (String key : CuratedContentFiles.KEYS) {
            int position = CuratedContentFiles.KEYS.indexOf(key);
            var review = new CuratedContent.Review("合成音乐介绍 [R1]", List.of("R1"));
            albums.add(
                    new CuratedContent.Album(
                            key,
                            CuratedContentFiles.TITLES.get(position),
                            CuratedContentFiles.ARTISTS.get(position),
                            2000,
                            null,
                            List.of("https://example.org/album"),
                            List.of("jazz"),
                            "ready",
                            review,
                            review,
                            List.of(
                                    new CuratedContent.Source(
                                            "R1",
                                            "https://example.org/review",
                                            "合成资料",
                                            "测试",
                                            "2026-11-09",
                                            "合成测试的声音描述")),
                            List.of()));
        }
        return new CuratedContent("auralis-curated-v1", albums);
    }

    @Test
    void importsIndependentFilesAndRoundTripsWithoutInventingIdentity() throws Exception {
        var input = temp.resolve("input.json");
        JSON.writeValue(input.toFile(), content());
        var destination = temp.resolve("prepared");
        CuratedContentFiles.importFile(input, destination);
        assertEquals(content(), CuratedContentFiles.load(destination));
        assertNull(
                CuratedContentFiles.load(destination)
                        .albums()
                        .getFirst()
                        .musicbrainz_release_group_id());
        for (String key : CuratedContentFiles.KEYS) {
            assertTrue(Files.exists(destination.resolve(key).resolve("short-review.json")));
            assertTrue(Files.exists(destination.resolve(key).resolve("long-review.json")));
            assertFalse(
                    Files.readString(destination.resolve(key).resolve("album.json"))
                            .contains("short_review"));
        }
        assertThrows(
                FileAlreadyExistsException.class,
                () -> CuratedContentFiles.importFile(input, destination));
    }

    @Test
    void invalidReferenceCannotPartiallyImport() throws Exception {
        var input = temp.resolve("input.json");
        var json = JSON.valueToTree(content());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        json.path("albums").get(0).path("short_review"))
                .put("text", "没有登记的引用 [R2]");
        JSON.writeValue(input.toFile(), json);
        var destination = temp.resolve("rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> CuratedContentFiles.importFile(input, destination));
        assertFalse(Files.exists(destination));
    }

    @Test
    void rejectsReorderingUnknownFieldsAndDuplicateJsonKeys() throws Exception {
        var values = new ArrayList<>(content().albums());
        Collections.swap(values, 0, 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CuratedContentFiles.validate(
                                new CuratedContent("auralis-curated-v1", values)));
        var input = temp.resolve("input.json");
        Files.writeString(
                input, JSON.writeValueAsString(content()).replaceFirst("\\{", "{\"extra\":1,"));
        assertThrows(java.io.IOException.class, () -> CuratedContentFiles.read(input));
        Files.writeString(
                input, "{\"format_version\":\"a\",\"format_version\":\"b\",\"albums\":[]}");
        assertThrows(java.io.IOException.class, () -> CuratedContentFiles.read(input));
    }
}
