package io.auralis.session.adapters;

import io.auralis.persistence.LockOrder;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.persistence.ShortTransactions;
import io.auralis.session.api.SessionStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSessionStore implements SessionStore {
    private final JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entities;

    public PostgresSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createSubject(String id, String digest, Instant at, Instant expires) {
        ShortTransactions.requireWrite();
        jdbc.update(
                "INSERT INTO discovery.subject(id,credential_digest,lifecycle,last_user_action,expires_at) VALUES (?,?,'ACTIVE',?,?)",
                id,
                digest,
                Timestamp.from(at),
                Timestamp.from(expires));
    }

    @Override
    public void createSession(
            String id,
            String subject,
            long inputRevision,
            String input,
            Instant at,
            Instant expires) {
        ShortTransactions.requireWrite();
        lockSubject(subject);
        Integer valid =
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.subject WHERE id=? AND lifecycle='ACTIVE' AND expires_at>clock_timestamp()",
                        Integer.class,
                        subject);
        if (valid == null || valid != 1) throw new PersistenceRejected("SUBJECT_UNAVAILABLE");
        var entity = new SessionEntity();
        entity.id = id;
        entity.subjectId = subject;
        entity.inputRevision = inputRevision;
        entity.rawInput = input;
        entity.status = "ACTIVE";
        entity.createdAt = at;
        entity.lastUserAction = at;
        entity.expiresAt = expires;
        entities.persist(entity);
        entities.flush();
        jdbc.update(
                "UPDATE discovery.subject SET current_session_id=?,revision=revision+1 WHERE id=?",
                id,
                subject);
    }

    private void lockSubject(String subject) {
        LockOrder.before(LockOrder.Level.SUBJECT, subject);
        jdbc.queryForList("SELECT id FROM discovery.subject WHERE id=? FOR UPDATE", subject);
    }

    @Override
    public View lockActive(Scope scope) {
        lockSubject(scope.subjectId());
        LockOrder.before(LockOrder.Level.SESSION, scope.sessionId());
        var rows =
                jdbc.query(
                        "SELECT s.status,s.expires_at FROM discovery.session s JOIN discovery.subject u ON u.id=s.subject_id "
                                + "WHERE s.id=? AND s.subject_id=? AND s.epoch=? AND s.revision=? AND u.epoch=? "
                                + "AND s.status='ACTIVE' AND u.lifecycle='ACTIVE' AND u.current_session_id=s.id "
                                + "AND s.expires_at>clock_timestamp() AND u.expires_at>clock_timestamp() FOR UPDATE OF s",
                        (rs, n) -> new View(scope, rs.getString(1), rs.getTimestamp(2).toInstant()),
                        scope.sessionId(),
                        scope.subjectId(),
                        scope.sessionEpoch(),
                        scope.revision(),
                        scope.subjectEpoch());
        if (rows.isEmpty()) throw new PersistenceRejected("STALE_SESSION_OR_BARRIER");
        return rows.getFirst();
    }

    @Override
    public Optional<Snapshot> current(String subject) {
        return jdbc
                .query(
                        "SELECT s.*,u.epoch AS subject_epoch FROM discovery.subject u JOIN discovery.session s ON s.id=u.current_session_id AND s.subject_id=u.id "
                                + "WHERE u.id=? AND u.lifecycle='ACTIVE' AND s.status='ACTIVE' AND u.expires_at>clock_timestamp() AND s.expires_at>clock_timestamp()",
                        (rs, n) ->
                                new Snapshot(
                                        new Scope(
                                                subject,
                                                rs.getString("id"),
                                                rs.getLong("subject_epoch"),
                                                rs.getLong("epoch"),
                                                rs.getLong("revision")),
                                        rs.getString("status"),
                                        rs.getString("raw_input"),
                                        rs.getTimestamp("expires_at").toInstant()),
                        subject)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<View> status(String subject) {
        return jdbc
                .query(
                        "SELECT s.id,s.epoch,s.revision,s.status,s.expires_at,u.epoch AS subject_epoch "
                                + "FROM discovery.subject u JOIN discovery.session s ON s.id=u.current_session_id AND s.subject_id=u.id "
                                + "WHERE u.id=? AND u.lifecycle='ACTIVE' AND s.status='ACTIVE' AND u.expires_at>clock_timestamp() AND s.expires_at>clock_timestamp()",
                        (rs, n) ->
                                new View(
                                        new Scope(
                                                subject,
                                                rs.getString("id"),
                                                rs.getLong("subject_epoch"),
                                                rs.getLong("epoch"),
                                                rs.getLong("revision")),
                                        rs.getString("status"),
                                        rs.getTimestamp("expires_at").toInstant()),
                        subject)
                .stream()
                .findFirst();
    }

    @Override
    public boolean close(Scope scope, String status) {
        if (!java.util.Set.of("SUPERSEDED", "DELETED", "EXPIRED").contains(status))
            throw new IllegalArgumentException("INVALID_TERMINAL_STATE");
        lockSubject(scope.subjectId());
        LockOrder.before(LockOrder.Level.SESSION, scope.sessionId());
        entities.flush();
        int changed =
                jdbc.update(
                        "UPDATE discovery.session SET status=?,epoch=epoch+1,revision=revision+1 WHERE id=? AND revision=? AND epoch=? AND subject_id=? AND EXISTS (SELECT 1 FROM discovery.subject WHERE id=? AND epoch=?)",
                        status,
                        scope.sessionId(),
                        scope.revision(),
                        scope.sessionEpoch(),
                        scope.subjectId(),
                        scope.subjectId(),
                        scope.subjectEpoch());
        if (changed == 1)
            jdbc.update(
                    "UPDATE discovery.subject SET current_session_id=NULL,revision=revision+1 WHERE id=? AND current_session_id=?",
                    scope.subjectId(),
                    scope.sessionId());
        entities.clear();
        return changed == 1;
    }

    @Override
    public boolean revokeSubject(String subject, long revision, long epoch) {
        lockSubject(subject);
        entities.flush();
        int changed =
                jdbc.update(
                        "UPDATE discovery.subject SET lifecycle='REVOKED',epoch=epoch+1,revision=revision+1,current_session_id=NULL WHERE id=? AND revision=? AND epoch=? AND lifecycle='ACTIVE'",
                        subject,
                        revision,
                        epoch);
        entities.clear();
        return changed == 1;
    }

    @Override
    public boolean purgeSubject(String subject, long epoch) {
        lockSubject(subject);
        entities.flush();
        int changed =
                jdbc.update(
                        "DELETE FROM discovery.subject WHERE id=? AND epoch=? AND lifecycle IN ('REVOKED','EXPIRED')",
                        subject,
                        epoch);
        entities.clear();
        return changed == 1;
    }

    @Override
    public boolean purgeSession(Scope scope) {
        lockSubject(scope.subjectId());
        LockOrder.before(LockOrder.Level.SESSION, scope.sessionId());
        entities.flush();
        int changed =
                jdbc.update(
                        "DELETE FROM discovery.session WHERE id=? AND subject_id=? AND epoch=? AND revision=? AND status IN ('DELETED','EXPIRED') AND EXISTS (SELECT 1 FROM discovery.subject WHERE id=? AND epoch=?)",
                        scope.sessionId(),
                        scope.subjectId(),
                        scope.sessionEpoch(),
                        scope.revision(),
                        scope.subjectId(),
                        scope.subjectEpoch());
        entities.clear();
        return changed == 1;
    }

    @Override
    public void recordHeard(Scope scope, UUID album, Instant at) {
        lockActive(scope);
        jdbc.update(
                "INSERT INTO discovery.heard_album VALUES (?,?,?) ON CONFLICT(subject_id,release_group_id) DO NOTHING",
                scope.subjectId(),
                album,
                Timestamp.from(at));
    }

    @Override
    public void saveCommand(
            Scope scope,
            String operation,
            String command,
            String digest,
            String result,
            Instant at) {
        lockActive(scope);
        jdbc.update(
                "INSERT INTO discovery.command_receipt VALUES (?,?,?, ?,?::jsonb,?) ON CONFLICT(subject_id,operation,command_id) DO NOTHING",
                scope.subjectId(),
                operation,
                command,
                digest,
                result,
                Timestamp.from(at));
        command(scope, operation, command, digest)
                .orElseThrow(() -> new PersistenceRejected("COMMAND_MISSING"));
    }

    @Override
    public Optional<String> command(Scope scope, String operation, String command, String digest) {
        lockActive(scope);
        return jdbc
                .query(
                        "SELECT request_digest,result_ref::text FROM discovery.command_receipt WHERE subject_id=? AND operation=? AND command_id=?",
                        (rs, n) -> {
                            if (!digest.equals(rs.getString(1)))
                                throw new PersistenceRejected("IDEMPOTENCY_CONFLICT");
                            return rs.getString(2);
                        },
                        scope.subjectId(),
                        operation,
                        command)
                .stream()
                .findFirst();
    }

    @Override
    public void scheduleDeletion(String id, Scope scope, String step, Instant at) {
        ShortTransactions.requireWrite();
        // May run after a barrier: no content is restored, only bounded cleanup metadata is stored.
        lockSubject(scope.subjectId());
        int inserted =
                jdbc.update(
                        "INSERT INTO discovery.deletion_job(id,subject_id,session_id,barrier_epoch,state,step,next_attempt_at) "
                                + "SELECT ?,s.subject_id,s.id,s.epoch,'PENDING',?,? FROM discovery.session s JOIN discovery.subject u ON u.id=s.subject_id "
                                + "WHERE s.id=? AND s.subject_id=? AND s.epoch=? AND u.epoch=? "
                                + "AND (s.status IN ('DELETED','EXPIRED') OR u.lifecycle<>'ACTIVE')",
                        id,
                        step,
                        Timestamp.from(at),
                        scope.sessionId(),
                        scope.subjectId(),
                        scope.sessionEpoch(),
                        scope.subjectEpoch());
        if (inserted != 1) throw new PersistenceRejected("DELETION_BARRIER_REQUIRED");
    }
}
