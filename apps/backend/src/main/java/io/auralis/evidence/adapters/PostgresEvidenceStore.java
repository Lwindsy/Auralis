package io.auralis.evidence.adapters;

import io.auralis.evidence.api.EvidenceStore;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.session.api.SessionStore;
import io.auralis.session.api.SessionStore.Scope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresEvidenceStore implements EvidenceStore {
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;

    public PostgresEvidenceStore(JdbcTemplate jdbc, SessionStore sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    private static Timestamp timestamp(Instant time) {
        return time == null ? null : Timestamp.from(time);
    }

    @Override
    public void claim(
            Scope scope, String round, String execution, String read, String digest, Instant at) {
        sessions.lockActive(scope);
        jdbc.update(
                "INSERT INTO discovery.read_claim VALUES (?,?,?,?,?,?) ON CONFLICT(execution_id,read_id,url_digest) DO NOTHING",
                scope.sessionId(),
                round,
                execution,
                read,
                digest,
                timestamp(at));
    }

    @Override
    public void saveReceipt(Scope scope, Receipt r) {
        sessions.lockActive(scope);
        int inserted =
                jdbc.update(
                        "INSERT INTO discovery.read_receipt VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO NOTHING",
                        r.id(),
                        scope.sessionId(),
                        r.round(),
                        r.execution(),
                        r.candidate(),
                        r.album(),
                        r.finalUrl(),
                        timestamp(r.readAt()),
                        r.status(),
                        r.contentDigest(),
                        r.payload());
        if (inserted == 0) {
            Integer same =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.read_receipt WHERE id=? AND session_id=? AND round_id=? AND execution_id=? AND candidate_id=? AND release_group_id=? AND final_url IS NOT DISTINCT FROM ? AND read_at=? AND status=? AND content_digest IS NOT DISTINCT FROM ? AND receipt=?::jsonb",
                            Integer.class,
                            r.id(),
                            scope.sessionId(),
                            r.round(),
                            r.execution(),
                            r.candidate(),
                            r.album(),
                            r.finalUrl(),
                            timestamp(r.readAt()),
                            r.status(),
                            r.contentDigest(),
                            r.payload());
            if (same == null || same != 1) throw new PersistenceRejected("IDEMPOTENCY_CONFLICT");
        }
    }

    @Override
    public void saveSeed(Scope scope, Seed s) {
        sessions.lockActive(scope);
        int inserted =
                jdbc.update(
                        "INSERT INTO discovery.seed_evidence VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(session_id,seed_id,identity_revision) DO NOTHING",
                        scope.sessionId(),
                        s.seedId(),
                        s.identityRevision(),
                        s.dataRevision(),
                        s.readId(),
                        s.sourceReadId(),
                        s.contentDigest(),
                        timestamp(s.readAt()),
                        timestamp(s.expiresAt()),
                        s.status(),
                        s.evidence());
        if (inserted == 0) {
            Integer same =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.seed_evidence WHERE session_id=? AND seed_id=? AND identity_revision=? AND data_revision=? AND read_id IS NOT DISTINCT FROM ? AND source_read_id IS NOT DISTINCT FROM ? AND content_digest IS NOT DISTINCT FROM ? AND read_at IS NOT DISTINCT FROM ? AND expires_at=? AND status=? AND evidence=?::jsonb",
                            Integer.class,
                            scope.sessionId(),
                            s.seedId(),
                            s.identityRevision(),
                            s.dataRevision(),
                            s.readId(),
                            s.sourceReadId(),
                            s.contentDigest(),
                            timestamp(s.readAt()),
                            timestamp(s.expiresAt()),
                            s.status(),
                            s.evidence());
            if (same == null || same != 1) throw new PersistenceRejected("IDEMPOTENCY_CONFLICT");
        }
    }

    @Override
    public Optional<String> seed(Scope scope, String seed, String revision) {
        sessions.lockActive(scope);
        return jdbc
                .query(
                        "SELECT evidence::text FROM discovery.seed_evidence WHERE session_id=? AND seed_id=? AND identity_revision=? AND expires_at>clock_timestamp()",
                        (rs, n) -> rs.getString(1),
                        scope.sessionId(),
                        seed,
                        revision)
                .stream()
                .findFirst();
    }
}
