package io.auralisy.catalog.adapters;

import io.auralisy.catalog.api.AudienceStore;
import io.auralisy.persistence.PersistenceRejected;
import io.auralisy.persistence.ShortTransactions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAudienceStore implements AudienceStore {
    private final JdbcTemplate jdbc;

    public PostgresAudienceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void stage(Snapshot s) {
        ShortTransactions.requireWrite();
        jdbc.update(
                "INSERT INTO discovery.audience_snapshot(id,dataset_id,listening_window,sample_listeners,manifest_digest,snapshot_fingerprint,recall_binding) VALUES (?,?,?,?,?,?,?::jsonb)",
                s.id(),
                s.dataset(),
                s.window(),
                s.sampleListeners(),
                s.manifestDigest(),
                s.fingerprint(),
                s.recallBinding());
    }

    @Override
    public void putCounts(String snapshot, String window, long sample, Map<UUID, Long> counts) {
        ShortTransactions.requireWrite();
        if (counts.size() > 1000) throw new IllegalArgumentException("BATCH_TOO_LARGE");
        var active =
                jdbc.queryForObject(
                        "SELECT active FROM discovery.audience_snapshot WHERE id=? AND listening_window=? FOR UPDATE",
                        Boolean.class,
                        snapshot,
                        window);
        if (Boolean.TRUE.equals(active)) throw new PersistenceRejected("ACTIVE_SNAPSHOT_IMMUTABLE");
        jdbc.batchUpdate(
                "INSERT INTO discovery.audience_count(snapshot_id,listening_window,sample_listeners,release_group_id,listeners) VALUES (?,?,?,?,?)",
                counts.entrySet(),
                1000,
                (s, e) -> {
                    s.setString(1, snapshot);
                    s.setString(2, window);
                    s.setLong(3, sample);
                    s.setObject(4, e.getKey());
                    s.setLong(5, e.getValue());
                });
    }

    @Override
    public Map<UUID, Long> counts(String snapshot, String window, List<UUID> albums) {
        if (albums.size() > 1000) throw new IllegalArgumentException("BATCH_TOO_LARGE");
        String marks =
                albums.isEmpty()
                        ? "NULL"
                        : String.join(",", java.util.Collections.nCopies(albums.size(), "?"));
        var args = new java.util.ArrayList<Object>();
        args.addAll(albums);
        args.add(snapshot);
        args.add(window);
        var result = new LinkedHashMap<UUID, Long>();
        var found = new boolean[] {false};
        jdbc.query(
                "SELECT s.id,c.release_group_id,c.listeners FROM discovery.audience_snapshot s "
                        + "LEFT JOIN discovery.audience_count c ON c.snapshot_id=s.id AND c.listening_window=s.listening_window AND c.release_group_id IN ("
                        + marks
                        + ") "
                        + "WHERE s.id=? AND s.listening_window=? AND s.active",
                rs -> {
                    found[0] = true;
                    UUID album = rs.getObject(2, UUID.class);
                    if (album != null) result.put(album, rs.getLong(3));
                },
                args.toArray());
        if (!found[0]) throw new PersistenceRejected("AUDIENCE_BINDING_MISMATCH");
        return Map.copyOf(result); // Absent IDs remain absent, never invented as zero.
    }
}
