package io.auralisy.catalog.adapters;

import io.auralisy.catalog.api.*;
import io.auralisy.persistence.PersistenceRejected;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAudienceQuery implements AudienceQuery {
    private final JdbcTemplate jdbc;
    private final AudienceStore store;

    public PostgresAudienceQuery(JdbcTemplate jdbc, AudienceStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    private String searchSnapshot;
    private Map<String, Long> searchCounts = Map.of();

    /** Optional search ranking signal; missing statistics never remove a directory result. */
    public synchronized Map<String, Long> searchPopularity() {
        var snapshots =
                jdbc.queryForList(
                        "SELECT s.id FROM discovery.audience_snapshot s JOIN ingestion.active_dataset a ON (s.recall_binding->>'catalog_dataset_id')::bigint=a.dataset_id JOIN ingestion.dataset d ON d.id=a.dataset_id WHERE s.active AND s.snapshot_fingerprint=d.manifest_sha256 AND s.recall_binding->>'scope'='FULL_CATALOG'",
                        String.class);
        if (snapshots.isEmpty()) {
            searchSnapshot = null;
            searchCounts = Map.of();
            return searchCounts;
        }
        String snapshot = snapshots.getFirst();
        if (!snapshot.equals(searchSnapshot)) {
            var counts = new HashMap<String, Long>();
            jdbc.query(
                    "SELECT release_group_id::text,listeners FROM discovery.audience_count WHERE snapshot_id=? AND listeners>0",
                    rs -> {
                        counts.put(rs.getString(1), rs.getLong(2));
                    },
                    snapshot);
            searchCounts = Collections.unmodifiableMap(counts);
            searchSnapshot = snapshot;
        }
        return searchCounts;
    }

    @Override
    public Map<UUID, Long> audience(long catalogDataset, Binding binding, List<UUID> albums) {
        // Legacy storage column names retain V7 compatibility; the contents are an independently
        // admitted full-catalog source binding, never training weights or training fingerprints.
        var match =
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM discovery.audience_snapshot s JOIN ingestion.active_dataset a ON a.dataset_id=? JOIN ingestion.dataset d ON d.id=a.dataset_id WHERE s.id=? AND s.active AND s.dataset_id=? AND s.listening_window=? AND s.sample_listeners=? AND s.manifest_digest=? AND s.snapshot_fingerprint=d.manifest_sha256 AND s.recall_binding->>'scope'='FULL_CATALOG' AND (s.recall_binding->>'catalog_dataset_id')::bigint=a.dataset_id)",
                        Boolean.class,
                        catalogDataset,
                        binding.snapshotDigest(),
                        binding.dataset(),
                        binding.window(),
                        binding.sampleListeners(),
                        binding.snapshotDigest());
        if (!Boolean.TRUE.equals(match)) throw new PersistenceRejected("AUDIENCE_BINDING_MISMATCH");
        return store.counts(binding.snapshotDigest(), binding.window(), albums);
    }
}
