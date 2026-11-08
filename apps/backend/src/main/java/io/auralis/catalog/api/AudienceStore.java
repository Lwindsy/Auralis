package io.auralis.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Only the projection store; artifact validation and maintenance activation are BE-007
 * responsibilities.
 */
public interface AudienceStore {
    record Snapshot(
            String id,
            String dataset,
            String window,
            long sampleListeners,
            String manifestDigest,
            String fingerprint,
            String recallBinding) {}

    void stage(Snapshot snapshot);

    void putCounts(String snapshot, String window, long sampleListeners, Map<UUID, Long> counts);

    Map<UUID, Long> counts(String snapshot, String window, List<UUID> albums);
}
