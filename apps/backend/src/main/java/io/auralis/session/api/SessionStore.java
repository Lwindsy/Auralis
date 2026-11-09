package io.auralis.session.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port. Credential creation, expiry policy and HTTP authorization belong to BE-006. */
public interface SessionStore {
    record Scope(
            String subjectId,
            String sessionId,
            long subjectEpoch,
            long sessionEpoch,
            long revision) {}

    record Snapshot(Scope scope, String status, String rawInput, Instant expiresAt) {}

    record View(Scope scope, String status, Instant expiresAt) {}

    void createSubject(String id, String credentialDigest, Instant actionAt, Instant expiresAt);

    void createSession(
            String id,
            String subjectId,
            long inputRevision,
            String rawInput,
            Instant createdAt,
            Instant expiresAt);

    View lockActive(Scope scope);

    Scope lockRefreshed(Scope original);

    Scope advanceRevision(Scope scope);

    Optional<View> status(String subjectId);

    Optional<Snapshot> current(String subjectId);

    /**
     * Presentation revisions may advance; identity and revocation epochs may never be refreshed.
     */
    default Scope refresh(Scope original) {
        var latest =
                current(original.subjectId())
                        .orElseThrow(
                                () ->
                                        new io.auralis.persistence.PersistenceRejected(
                                                "EXECUTION_GONE"))
                        .scope();
        if (!latest.sessionId().equals(original.sessionId())
                || latest.subjectEpoch() != original.subjectEpoch()
                || latest.sessionEpoch() != original.sessionEpoch())
            throw new io.auralis.persistence.PersistenceRejected("EXECUTION_GONE");
        return latest;
    }

    long inputRevision(Scope scope);

    List<UUID> heardAlbums(Scope scope);

    boolean close(Scope scope, String terminalStatus);

    boolean revokeSubject(String id, long expectedRevision, long expectedEpoch);

    boolean purgeSubject(String id, long expectedEpoch);

    boolean purgeSession(Scope scope);

    void forgetHeard(Scope scope, UUID releaseGroupId);

    void recordHeard(Scope scope, UUID releaseGroupId, Instant at);

    void saveCommand(
            Scope scope,
            String operation,
            String commandId,
            String digest,
            String result,
            Instant at);

    Optional<String> command(Scope scope, String operation, String commandId, String digest);

    void scheduleDeletion(String id, Scope scope, Instant at);

    void lockFinancialOwner(String sessionId);
}
