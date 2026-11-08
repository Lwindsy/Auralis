package io.auralis.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.auralis.catalog.adapters.*;
import io.auralis.catalog.api.CatalogFailure;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneCatalogSearchTest {
    @TempDir Path temp;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ID = "11111111-1111-4111-8111-111111111111",
            OLD = "22222222-2222-4222-8222-222222222222";

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String fixture(Path source) throws Exception {
        Files.createDirectories(source);
        var row =
                JSON.createObjectNode()
                        .put("id", ID)
                        .put("title", "Début")
                        .put("artist", "Björk")
                        .put("year", 1993)
                        .put("type", "Album");
        row.putArray("genres");
        row.putArray("aliases").add("比约克 首张专辑").add("!!!").add("🎵");
        row.putArray("redirects").add(OLD);
        byte[] bytes =
                (JSON.writeValueAsString(row) + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source.resolve("albums.jsonl"), bytes);
        var manifest =
                JSON.createObjectNode()
                        .put("format_version", 1)
                        .put("scope", "ALL_MUSICBRAINZ_RELEASE_GROUPS")
                        .put("rows", 1)
                        .put("albums_sha256", hash(bytes))
                        .put("source_receipt_sha256", "a".repeat(64));
        manifest.putObject("source_tables")
                .putObject("release_group")
                .put("rows", 1)
                .put("sha256", "a".repeat(64));
        byte[] m = JSON.writeValueAsBytes(manifest);
        Files.write(source.resolve("manifest.json"), m);
        return hash(m);
    }

    @Test
    void realIndexSearchesUnicodeAccentsArtistAndAliasWithoutNetwork() throws Exception {
        Path source = temp.resolve("source"), index = temp.resolve("index");
        String digest = fixture(source);
        CatalogIndexBuild.build(source, index, digest);
        try (var search = new LuceneCatalogSearch(index, digest)) {
            for (String q : List.of("debut", "Björk", "Bjo", "比约克", "Björk Debut", "!!!", "🎵"))
                assertEquals(ID, search.search(q, 0, 10).albums().getFirst().id().toString(), q);
            assertTrue(search.search("no-such-album", 0, 10).albums().isEmpty());
            assertTrue(search.search("debut", 1, 10).albums().isEmpty());
            assertEquals(ID, search.resolve(UUID.fromString(OLD)).id().toString());
            assertTrue(search.resolve(UUID.fromString(ID)).genres().isEmpty());
            assertEquals(
                    404,
                    assertThrows(CatalogFailure.class, () -> search.resolve(UUID.randomUUID()))
                            .status());
        }
    }

    @Test
    void changedInputAndUnfinishedIndexAreRejectedAndNeverOverwriteExistingData() throws Exception {
        Path source = temp.resolve("source"), index = temp.resolve("index");
        String digest = fixture(source);
        Files.writeString(source.resolve("albums.jsonl"), "\n", StandardOpenOption.APPEND);
        assertThrows(Exception.class, () -> CatalogIndexBuild.build(source, index, digest));
        assertFalse(Files.exists(index.resolve("manifest.sha256")));
        assertThrows(Exception.class, () -> new LuceneCatalogSearch(index, digest));
        assertThrows(Exception.class, () -> CatalogIndexBuild.build(source, index, digest));
    }

    @Test
    void manifestMismatchAndWrongBoundSnapshotFailBeforeServing() throws Exception {
        Path source = temp.resolve("source"), index = temp.resolve("index");
        String digest = fixture(source);
        assertThrows(Exception.class, () -> CatalogIndexBuild.build(source, index, "0".repeat(64)));
        assertFalse(Files.exists(index));
        CatalogIndexBuild.build(source, index, digest);
        assertThrows(Exception.class, () -> new LuceneCatalogSearch(index, "0".repeat(64)));
    }
}
