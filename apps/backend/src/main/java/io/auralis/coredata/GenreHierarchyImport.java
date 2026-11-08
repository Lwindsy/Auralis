package io.auralis.coredata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Imports a source-attributed hierarchy supplement for local private development. */
public final class GenreHierarchyImport {
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !args[4].equals("private-development")) {
            throw new IllegalArgumentException(
                    "Expected private-config annotations approved-sha source-sha private-development");
        }
        byte[] bytes = Files.readAllBytes(Path.of(args[1]));
        String hash = CoreDatabase.sha(bytes);
        if (!hash.equals(args[2])) throw new IllegalArgumentException("ANNOTATION_HASH_MISMATCH");
        var document = CoreDatabase.JSON.readTree(bytes);
        if (document.path("format_version").asInt() != 1
                || !document.path("hierarchy_source").path("sha256").asText().equals(args[3])) {
            throw new IllegalArgumentException("SOURCE_MISMATCH");
        }
        var app = new CoreDatabase(Path.of(args[0]));
        try (var connection = app.connect("importer")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(194024)");
            }
            long dataset;
            try (var statement =
                    connection.prepareStatement(
                            "SELECT d.id FROM ingestion.dataset d JOIN ingestion.active_dataset a ON a.dataset_id=d.id WHERE d.manifest_sha256=?")) {
                statement.setString(
                        1, document.path("core_snapshot").path("manifest_sha256").asText());
                try (var result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("ACTIVE_SNAPSHOT_MISMATCH");
                    dataset = result.getLong(1);
                }
            }
            var names = new HashMap<String, String>();
            try (var statement =
                    connection.prepareStatement(
                            "SELECT mbid::text,name FROM catalog.genre WHERE dataset_id=?")) {
                statement.setLong(1, dataset);
                try (var result = statement.executeQuery()) {
                    while (result.next()) names.put(result.getString(1), result.getString(2));
                }
            }
            var seen = new HashSet<String>();
            var statuses =
                    Set.of(
                            "classified",
                            "uncategorised",
                            "ambiguous",
                            "no_strict_match",
                            "other_rym_section_only");
            for (var row : document.path("genres")) {
                String id = row.path("core_genre_mbid").asText();
                if (!row.path("core_name").asText().equals(names.get(id))
                        || !seen.add(id)
                        || !statuses.contains(row.path("status").asText())) {
                    throw new IllegalArgumentException("INVALID_GENRE_ANNOTATION");
                }
            }
            if (!seen.equals(names.keySet()))
                throw new IllegalArgumentException("INCOMPLETE_GENRE_SET");
            long batch;
            try (var statement =
                    connection.prepareStatement(
                            "INSERT INTO ingestion.genre_hierarchy_batch(dataset_id,annotation_sha256,source,purpose) VALUES(?,?,?::jsonb,?) ON CONFLICT(annotation_sha256) DO NOTHING RETURNING id")) {
                statement.setLong(1, dataset);
                statement.setString(2, hash);
                statement.setString(3, document.path("hierarchy_source").toString());
                statement.setString(4, args[4]);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        System.out.println("HIERARCHY_ALREADY_IMPORTED");
                        return;
                    }
                    batch = result.getLong(1);
                }
            }
            try (var statement =
                    connection.prepareStatement(
                            "INSERT INTO catalog.genre_hierarchy_annotation(batch_id,dataset_id,genre_mbid,status,annotation) VALUES(?,?,?::uuid,?,?::jsonb)")) {
                for (var row : document.path("genres")) {
                    statement.setLong(1, batch);
                    statement.setLong(2, dataset);
                    statement.setString(3, row.path("core_genre_mbid").asText());
                    statement.setString(4, row.path("status").asText());
                    statement.setString(5, row.toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
            System.out.println("HIERARCHY_IMPORTED batch=" + batch + " rows=" + seen.size());
        }
    }
}
