package io.auralis.catalog.adapters;

import static io.auralis.catalog.application.CatalogJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline maintenance only. No Spring component or request-time file scan. */
public final class FullAudienceImport {
    private final JdbcTemplate source;
    private final JdbcTemplate target;
    private final TransactionTemplate transaction;

    public FullAudienceImport(
            JdbcTemplate source, JdbcTemplate target, TransactionTemplate transaction) {
        this.source = source;
        this.target = target;
        this.transaction = transaction;
    }

    private String sourceManifest(long dataset) {
        var rows =
                source.queryForList(
                        "SELECT d.manifest_sha256 FROM ingestion.dataset d JOIN ingestion.active_dataset a ON a.dataset_id=d.id WHERE d.id=? AND d.status='ACTIVE'",
                        String.class,
                        dataset);
        if (rows.size() != 1) throw new IllegalArgumentException("CATALOG_VERSION_MISMATCH");
        return rows.getFirst();
    }

    private long sample(long dataset, int window) {
        // Maintenance-time denominator. The online path reads only the admitted projection.
        return source.queryForObject(
                "SELECT count(*) FROM statistics.observation o WHERE o.dataset_id=? AND o.entity_kind='album' AND o.window_year=? AND EXISTS(SELECT 1 FROM statistics.album_listen l WHERE l.dataset_id=o.dataset_id AND l.observation_id=o.id AND l.listen_count>0)",
                Long.class,
                dataset,
                window);
    }

    private void rows(
            long dataset, int window, java.util.function.BiConsumer<String, Long> consumer) {
        source.query(
                "SELECT a.mbid::text,coalesce(p.listeners,0) FROM catalog.current_album a LEFT JOIN statistics.current_album_popularity p ON p.dataset_id=a.dataset_id AND p.album_id=a.id AND p.window_year=? WHERE a.dataset_id=? ORDER BY a.mbid",
                rs -> {
                    consumer.accept(rs.getString(1), rs.getLong(2));
                },
                window,
                dataset);
    }

    public ObjectNode export(long dataset, int window, String expectedManifest, Path output)
            throws Exception {
        if (window != 0 && (window < 1900 || window > 9998))
            throw new IllegalArgumentException("INVALID_WINDOW");
        if (!sourceManifest(dataset).equals(expectedManifest))
            throw new IllegalArgumentException("SOURCE_MISMATCH");
        var artifact =
                JSON.createObjectNode()
                        .put("format_version", 2)
                        .put("scope", "FULL_CATALOG")
                        .put("source", "LISTENBRAINZ_SAMPLE")
                        .put("catalog_dataset_id", dataset)
                        .put("source_manifest_sha256", expectedManifest)
                        .put("dataset_id", "core:" + expectedManifest)
                        .put("window", Integer.toString(window))
                        .put("sample_listeners", sample(dataset, window));
        var counts = artifact.putArray("counts");
        rows(
                dataset,
                window,
                (id, n) -> counts.addObject().put("release_group_id", id).put("listeners", n));
        artifact.put("digest", digest(artifact));
        validate(artifact);
        if (!sourceManifest(dataset).equals(expectedManifest))
            throw new IllegalArgumentException("SOURCE_CHANGED");
        try (var out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
            JSON.writeValue(out, artifact);
        }
        return receipt(artifact);
    }

    public ObjectNode admit(
            Path file,
            String expectedDigest,
            long expectedDataset,
            String expectedManifest,
            String expectedWindow)
            throws Exception {
        if (Files.size(file) > 2_000_000_000L)
            throw new IllegalArgumentException("ARTIFACT_TOO_LARGE");
        var artifact = JSON.readTree(file.toFile());
        validate(artifact);
        if (!artifact.path("digest").asText().equals(expectedDigest)
                || artifact.path("catalog_dataset_id").asLong() != expectedDataset
                || !artifact.path("source_manifest_sha256").asText().equals(expectedManifest)
                || !artifact.path("window").asText().equals(expectedWindow)
                || !sourceManifest(expectedDataset).equals(expectedManifest))
            throw new IllegalArgumentException("SOURCE_MISMATCH");
        int window = Integer.parseInt(expectedWindow);
        if (sample(expectedDataset, window) != artifact.path("sample_listeners").asLong())
            throw new IllegalArgumentException("POPULATION_MISMATCH");
        var iterator = artifact.path("counts").elements();
        rows(
                expectedDataset,
                window,
                (id, n) -> {
                    if (!iterator.hasNext())
                        throw new IllegalArgumentException("INCOMPLETE_COUNTS");
                    var row = iterator.next();
                    if (!row.path("release_group_id").asText().equals(id)
                            || row.path("listeners").asLong() != n)
                        throw new IllegalArgumentException("COUNT_MISMATCH");
                });
        if (iterator.hasNext()) throw new IllegalArgumentException("EXTRA_COUNTS");
        if (!sourceManifest(expectedDataset).equals(expectedManifest))
            throw new IllegalArgumentException("SOURCE_CHANGED");
        transaction.executeWithoutResult(
                status -> {
                    // Serialize maintenance activations. Never share the short online transaction
                    // deadline.
                    target.execute("LOCK TABLE discovery.execution_slot IN SHARE MODE");
                    target.execute("LOCK TABLE discovery.audience_snapshot IN EXCLUSIVE MODE");
                    target.execute("LOCK TABLE ingestion.active_dataset IN SHARE MODE");
                    var targetManifest =
                            target.queryForObject(
                                    "SELECT d.manifest_sha256 FROM ingestion.dataset d JOIN ingestion.active_dataset a ON a.dataset_id=d.id WHERE d.id=?",
                                    String.class,
                                    expectedDataset);
                    if (!expectedManifest.equals(targetManifest))
                        throw new IllegalArgumentException("TARGET_CATALOG_MISMATCH");
                    var activeExecutions =
                            target.queryForObject(
                                    "SELECT count(*) FROM discovery.execution_slot WHERE state<>'FREE'",
                                    Long.class);
                    if (activeExecutions != 0)
                        throw new IllegalArgumentException("MAINTENANCE_REQUIRES_DRAIN");
                    var existing =
                            target.queryForList(
                                    "SELECT id FROM discovery.audience_snapshot WHERE id=?",
                                    String.class,
                                    expectedDigest);
                    if (existing.isEmpty()) {
                        var binding =
                                JSON.createObjectNode()
                                        .put("scope", "FULL_CATALOG")
                                        .put("catalog_dataset_id", expectedDataset)
                                        .put("source_manifest_sha256", expectedManifest);
                        target.update(
                                "INSERT INTO discovery.audience_snapshot(id,dataset_id,listening_window,sample_listeners,manifest_digest,snapshot_fingerprint,recall_binding) VALUES(?,?,?,?,?,?,?::jsonb)",
                                expectedDigest,
                                artifact.path("dataset_id").asText(),
                                expectedWindow,
                                artifact.path("sample_listeners").asLong(),
                                expectedDigest,
                                expectedManifest,
                                binding.toString());
                        var batch = new ArrayList<Object[]>(1000);
                        for (var row : artifact.path("counts")) {
                            batch.add(
                                    new Object[] {
                                        expectedDigest,
                                        expectedWindow,
                                        artifact.path("sample_listeners").asLong(),
                                        UUID.fromString(row.path("release_group_id").asText()),
                                        row.path("listeners").asLong()
                                    });
                            if (batch.size() == 1000) {
                                insert(batch);
                                batch.clear();
                            }
                        }
                        if (!batch.isEmpty()) insert(batch);
                    }
                    // Re-admission checks stored rows as well as the file; damaged projections
                    // cannot be reactivated.
                    boolean metadataMatches =
                            Boolean.TRUE.equals(
                                    target.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM discovery.audience_snapshot WHERE id=? AND manifest_digest=? AND dataset_id=? AND listening_window=? AND sample_listeners=? AND snapshot_fingerprint=? AND recall_binding->>'scope'='FULL_CATALOG' AND recall_binding->>'catalog_dataset_id'=? AND recall_binding->>'source_manifest_sha256'=?)",
                                            Boolean.class,
                                            expectedDigest,
                                            expectedDigest,
                                            artifact.path("dataset_id").asText(),
                                            expectedWindow,
                                            artifact.path("sample_listeners").asLong(),
                                            expectedManifest,
                                            Long.toString(expectedDataset),
                                            expectedManifest));
                    if (!metadataMatches)
                        throw new IllegalArgumentException("STORED_BINDING_MISMATCH");
                    var stored =
                            target.queryForList(
                                    "SELECT release_group_id::text id,listeners FROM discovery.audience_count WHERE snapshot_id=? ORDER BY release_group_id",
                                    expectedDigest);
                    if (stored.size() != artifact.path("counts").size())
                        throw new IllegalArgumentException("STORED_COUNT_MISMATCH");
                    int index = 0;
                    for (var row : artifact.path("counts")) {
                        var actual = stored.get(index++);
                        if (!row.path("release_group_id").asText().equals(actual.get("id"))
                                || row.path("listeners").asLong()
                                        != ((Number) actual.get("listeners")).longValue())
                            throw new IllegalArgumentException("STORED_COUNT_MISMATCH");
                    }
                    target.update(
                            "UPDATE discovery.audience_snapshot SET active=false WHERE active AND id<>?",
                            expectedDigest);
                    target.update(
                            "UPDATE discovery.audience_snapshot SET active=true WHERE id=?",
                            expectedDigest);
                });
        return receipt(artifact);
    }

    private void insert(List<Object[]> rows) {
        target.batchUpdate(
                "INSERT INTO discovery.audience_count(snapshot_id,listening_window,sample_listeners,release_group_id,listeners) VALUES(?,?,?,?,?)",
                rows);
    }

    public static void validate(JsonNode artifact) {
        require("FullAudienceSnapshot", artifact);
        if (!contentDigest(artifact, "digest").equals(artifact.path("digest").asText())
                || !artifact.path("dataset_id")
                        .asText()
                        .equals("core:" + artifact.path("source_manifest_sha256").asText()))
            throw new IllegalArgumentException("DIGEST_MISMATCH");
        String previous = "";
        long sample = artifact.path("sample_listeners").asLong();
        for (var row : artifact.path("counts")) {
            String id = row.path("release_group_id").asText();
            if (!UUID.fromString(id).toString().equals(id)
                    || id.compareTo(previous) <= 0
                    || row.path("listeners").asLong() > sample)
                throw new IllegalArgumentException("INVALID_COUNTS");
            previous = id;
        }
    }

    private static ObjectNode receipt(JsonNode a) {
        return JSON.createObjectNode()
                .put("digest", a.path("digest").asText())
                .put("catalog_rows", a.path("counts").size())
                .put("sample_listeners", a.path("sample_listeners").asLong())
                .put("scope", "FULL_CATALOG");
    }
}
