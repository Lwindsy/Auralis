package io.auralisy.catalog.adapters;

import static io.auralisy.catalog.application.CatalogJson.JSON;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

/** Offline explicit import; a complete verified index is required before it can serve requests. */
public final class CatalogIndexBuild {
    private CatalogIndexBuild() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("USAGE: candidate index expected-manifest-sha256");
        build(Path.of(args[0]), Path.of(args[1]), args[2]);
    }

    public static void build(Path candidate, Path target, String expectedManifest)
            throws Exception {
        byte[] manifestBytes = Files.readAllBytes(candidate.resolve("manifest.json"));
        if (!sha(manifestBytes).equals(expectedManifest)) throw new IOException("MANIFEST_CHANGED");
        var manifest = JSON.readTree(manifestBytes);
        io.auralisy.catalog.application.CatalogJson.require("CatalogIndexManifest", manifest);
        long expected = manifest.path("rows").asLong(-1);
        if (manifest.path("format_version").asInt() != 1
                || !manifest.path("scope").asText().equals("ALL_MUSICBRAINZ_RELEASE_GROUPS")
                || expected < 1
                || expected != manifest.at("/source_tables/release_group/rows").asLong())
            throw new IOException("INCOMPLETE_DIRECTORY");
        Files.createDirectory(target);
        var checksum = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (var dir = FSDirectory.open(target);
                var analyzer = new CatalogAnalyzer();
                var writer =
                        new IndexWriter(
                                dir,
                                new IndexWriterConfig(analyzer)
                                        .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                                        .setRAMBufferSizeMB(96));
                var input =
                        new DigestInputStream(
                                Files.newInputStream(candidate.resolve("albums.jsonl")), checksum);
                var reader =
                        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 2_000_000) throw new IOException("ROW_TOO_LARGE");
                var row = JSON.readTree(line);
                UUID id = UUID.fromString(row.path("id").asText());
                String title = row.path("title").asText(), artist = row.path("artist").asText();
                if (title.isBlank() || artist.isBlank()) throw new IOException("MISSING_IDENTITY");
                io.auralisy.catalog.application.CatalogJson.require("CatalogIndexRow", row);
                var doc = new Document();
                doc.add(new StringField("id", id.toString(), Field.Store.YES));
                doc.add(new SortedDocValuesField("sort_id", new BytesRef(id.toString())));
                doc.add(new TextField("text", title + " " + artist, Field.Store.NO));
                doc.add(new StringField("exact", CatalogAnalyzer.exact(title), Field.Store.NO));
                doc.add(new StringField("exact", CatalogAnalyzer.exact(artist), Field.Store.NO));
                doc.add(new TextField("title", title, Field.Store.NO));
                doc.add(new TextField("artist", artist, Field.Store.NO));
                for (var alias : row.path("aliases")) {
                    doc.add(new TextField("text", alias.asText(), Field.Store.NO));
                    doc.add(
                            new StringField(
                                    "exact",
                                    CatalogAnalyzer.exact(alias.asText()),
                                    Field.Store.NO));
                }
                for (var alias : row.path("redirects"))
                    doc.add(
                            new StringField(
                                    "id",
                                    UUID.fromString(alias.asText()).toString(),
                                    Field.Store.NO));
                var stored = row.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) stored)
                        .remove(List.of("aliases", "redirects"));
                doc.add(new StoredField("album", JSON.writeValueAsString(stored)));
                writer.addDocument(doc);
                count++;
                if (count % 100000 == 0) System.out.println("INDEXED " + count);
            }
            if (count != expected
                    || !HexFormat.of()
                            .formatHex(checksum.digest())
                            .equals(manifest.path("albums_sha256").asText())) {
                writer.rollback();
                throw new IOException("DIRECTORY_CHANGED");
            }
            writer.setLiveCommitData(
                    Map.of(
                                    "format",
                                    "2",
                                    "manifest",
                                    expectedManifest,
                                    "rows",
                                    Long.toString(count),
                                    "source",
                                    manifest.path("source_receipt_sha256").asText())
                            .entrySet());
            writer.commit();
        }
        // Presence of this marker is mandatory; failed/partial builds are never admitted.
        Files.writeString(
                target.resolve("manifest.sha256"), expectedManifest, StandardOpenOption.CREATE_NEW);
        System.out.println("INDEX_COMPLETE " + count + " " + expectedManifest);
    }

    static String sha(byte[] value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
