package io.auralis.evidence.api;

import io.auralis.session.api.SessionStore.Scope;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceStore {
    record Receipt(
            String id,
            String round,
            String execution,
            String candidate,
            UUID album,
            String finalUrl,
            Instant readAt,
            String status,
            String contentDigest,
            String payload) {}

    record Seed(
            String seedId,
            String identityRevision,
            String dataRevision,
            String readId,
            String sourceReadId,
            String contentDigest,
            Instant readAt,
            Instant expiresAt,
            String status,
            String evidence) {}

    void claim(
            Scope scope,
            String round,
            String execution,
            String readId,
            String urlDigest,
            Instant at);

    void saveReceipt(Scope scope, Receipt receipt);

    void saveSeed(Scope scope, Seed seed);

    Optional<String> seed(Scope scope, String seedId, String identityRevision);
}
