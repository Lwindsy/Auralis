package io.auralis.catalog.adapters;

import io.auralis.catalog.api.*;
import io.auralis.persistence.PersistenceRejected;
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
