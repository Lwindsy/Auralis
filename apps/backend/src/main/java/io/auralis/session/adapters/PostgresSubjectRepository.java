package io.auralis.session.adapters;

import io.auralis.persistence.LockOrder;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.persistence.ShortTransactions;
import io.auralis.session.application.SubjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSubjectRepository implements SubjectRepository {
    private final JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entities;

    public PostgresSubjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Subject subject(ResultSet r, int n) throws SQLException {
        return new Subject(
                r.getString("id"),
                r.getString("credential_digest"),
                r.getLong("epoch"),
                r.getLong("revision"),
                r.getTimestamp("expires_at").toInstant(),
                r.getString("current_session_id"),
                r.getString("lifecycle"),
                r.getString("auth_generation"));
    }

    private Session session(ResultSet r, int n) throws SQLException {
        return new Session(
                r.getString("id"),
                r.getString("subject_id"),
                r.getLong("epoch"),
                r.getLong("revision"),
                r.getString("status"),
                r.getString("editable_input"),
                r.getTimestamp("expires_at").toInstant());
    }

    @Override
    public Optional<Subject> find(String digest, String generation, Instant now, boolean lock) {
        var found =
                jdbc
                        .query(
                                "SELECT s.* FROM discovery.subject s WHERE credential_digest=? AND auth_generation=? AND lifecycle='ACTIVE' AND expires_at>? AND NOT EXISTS (SELECT 1 FROM discovery.subject_revocation r WHERE r.credential_digest=s.credential_digest)",
                                this::subject,
                                digest,
                                generation,
                                Timestamp.from(now))
                        .stream()
                        .findFirst();
        if (lock && found.isPresent()) {
            LockOrder.before(LockOrder.Level.SUBJECT, found.get().id());
            return jdbc
                    .query(
                            "SELECT s.* FROM discovery.subject s WHERE id=? AND credential_digest=? AND auth_generation=? AND lifecycle='ACTIVE' AND expires_at>? AND NOT EXISTS (SELECT 1 FROM discovery.subject_revocation r WHERE r.credential_digest=s.credential_digest) FOR UPDATE",
                            this::subject,
                            found.get().id(),
                            digest,
                            generation,
                            Timestamp.from(now))
                    .stream()
                    .findFirst();
        }
        return found;
    }

    @Override
    public Optional<Subject> lockSubject(String id) {
        LockOrder.before(LockOrder.Level.SUBJECT, id);
        return jdbc
                .query("SELECT * FROM discovery.subject WHERE id=? FOR UPDATE", this::subject, id)
                .stream()
                .findFirst();
    }

    @Override
    public void lockSessions(String subject, List<String> ids) {
        for (String id : ids.stream().distinct().sorted().toList()) {
            LockOrder.before(LockOrder.Level.SESSION, id);
            jdbc.queryForList(
                    "SELECT id FROM discovery.session WHERE id=? AND subject_id=? FOR UPDATE",
                    id,
                    subject);
        }
    }

    @Override
    public boolean barrier(Subject s, String lifecycle) {
        ShortTransactions.requireWrite();
        if (!java.util.Set.of("REVOKED", "EXPIRED").contains(lifecycle))
            throw new PersistenceRejected("INVALID_BARRIER");
        return jdbc.update(
                        "UPDATE discovery.subject SET lifecycle=?,epoch=epoch+1,revision=revision+1,current_session_id=NULL WHERE id=? AND revision=? AND epoch=? AND lifecycle='ACTIVE'",
                        lifecycle,
                        s.id(),
                        s.revision(),
                        s.epoch())
                == 1;
    }

    @Override
    public void generation(String id, String generation) {
        ShortTransactions.requireWrite();
        jdbc.update(
                "UPDATE discovery.subject SET auth_generation=?,revision=revision+1 WHERE id=?",
                generation,
                id);
    }

    @Override
    public Optional<Session> session(String subject, String id) {
        return jdbc
                .query(
                        "SELECT id,subject_id,epoch,revision,status,editable_input,expires_at FROM discovery.session WHERE id=? AND subject_id=?",
                        this::session,
                        id,
                        subject)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> command(String subject, String key, String digest) {
        return jdbc
                .query(
                        "SELECT request_digest,result_ref::text FROM discovery.command_receipt WHERE subject_id=? AND operation='SUBMIT' AND command_id=?",
                        (r, n) -> {
                            if (!digest.equals(r.getString(1)))
                                throw new PersistenceRejected("IDEMPOTENCY_CONFLICT");
                            return r.getString(2);
                        },
                        subject,
                        key)
                .stream()
                .findFirst();
    }

    @Override
    public void command(String subject, String key, String digest, String result, Instant at) {
        ShortTransactions.requireWrite();
        jdbc.update(
                "INSERT INTO discovery.command_receipt VALUES (?,'SUBMIT',?,?,?::jsonb,?)",
                subject,
                key,
                digest,
                result,
                Timestamp.from(at));
    }

    @Override
    public long nextInputRevision(String subject) {
        return jdbc.queryForObject(
                "SELECT coalesce(max(input_revision),0)+1 FROM discovery.session WHERE subject_id=?",
                Long.class,
                subject);
    }

    @Override
    public void editable(String id, String input) {
        ShortTransactions.requireWrite();
        entities.flush();
        jdbc.update(
                "UPDATE discovery.session SET editable_input=?::jsonb,revision=revision+1 WHERE id=?",
                input,
                id);
        entities.clear();
    }

    @Override
    public void renew(String subject, String session, Instant at, Instant expires) {
        ShortTransactions.requireWrite();
        entities.flush();
        jdbc.update(
                "UPDATE discovery.subject SET last_user_action=?,expires_at=?,revision=revision+1 WHERE id=? AND lifecycle='ACTIVE'",
                Timestamp.from(at),
                Timestamp.from(expires),
                subject);
        if (session != null)
            jdbc.update(
                    "UPDATE discovery.session SET last_user_action=?,expires_at=?,revision=revision+1 WHERE id=? AND subject_id=? AND status='ACTIVE'",
                    Timestamp.from(at),
                    Timestamp.from(expires),
                    session,
                    subject);
        entities.clear();
    }

    @Override
    public Optional<Session> firstSession(String subject) {
        return jdbc
                .query(
                        "SELECT id,subject_id,epoch,revision,status,expires_at,NULL::jsonb AS editable_input FROM discovery.session WHERE subject_id=? ORDER BY id LIMIT 1",
                        this::session,
                        subject)
                .stream()
                .findFirst();
    }

    @Override
    public int trimSubjectRows(String subject, int limit) {
        ShortTransactions.requireWrite();
        int n =
                jdbc.update(
                        "DELETE FROM discovery.heard_album WHERE ctid IN (SELECT ctid FROM discovery.heard_album WHERE subject_id=? LIMIT ?)",
                        subject,
                        limit);
        return n
                + jdbc.update(
                        "DELETE FROM discovery.command_receipt WHERE ctid IN (SELECT ctid FROM discovery.command_receipt WHERE subject_id=? LIMIT ?)",
                        subject,
                        limit);
    }

    @Override
    public void tombstone(Subject subject, Instant now) {
        ShortTransactions.requireWrite();
        jdbc.update(
                "INSERT INTO discovery.subject_revocation VALUES (?,?,?) ON CONFLICT(credential_digest) DO NOTHING",
                subject.digest(),
                Timestamp.from(now),
                Timestamp.from(subject.expiresAt()));
    }

    @Override
    public void enqueueSubject(String subject, long epoch, Instant at) {
        ShortTransactions.requireWrite();
        jdbc.update(
                "INSERT INTO discovery.deletion_job(id,subject_id,barrier_epoch,state,step,next_attempt_at) VALUES (?,?,?,'PENDING','WAIT_RELEASE',?) ON CONFLICT DO NOTHING",
                UUID.randomUUID().toString(),
                subject,
                epoch,
                Timestamp.from(at));
    }

    @Override
    public void enqueueSession(Session session, Instant at) {
        ShortTransactions.requireWrite();
        jdbc.update(
                "INSERT INTO discovery.deletion_job(id,subject_id,session_id,barrier_epoch,state,step,next_attempt_at) VALUES (?,?,?,?,'PENDING','WAIT_RELEASE',?) ON CONFLICT DO NOTHING",
                UUID.randomUUID().toString(),
                session.subject(),
                session.id(),
                session.epoch(),
                Timestamp.from(at));
    }

    @Override
    public List<String> expiredSubjects(Instant now, String generation, int limit) {
        return jdbc.queryForList(
                "SELECT id FROM discovery.subject WHERE lifecycle='ACTIVE' AND (expires_at<=? OR auth_generation IS DISTINCT FROM ?) ORDER BY expires_at,id LIMIT ?",
                String.class,
                Timestamp.from(now),
                generation,
                limit);
    }

    @Override
    public List<Session> expiredSessions(Instant now, int limit) {
        return jdbc.query(
                "SELECT id,subject_id,epoch,revision,status,expires_at,NULL::jsonb AS editable_input FROM discovery.session WHERE status IN ('ACTIVE','SUPERSEDED') AND expires_at<=? ORDER BY expires_at,id LIMIT ?",
                this::session,
                Timestamp.from(now),
                limit);
    }

    @Override
    public Optional<Job> nextJob(Instant now) {
        return jdbc
                .query(
                        "SELECT id,subject_id,session_id,barrier_epoch FROM discovery.deletion_job WHERE state<>'COMPLETED' AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                        (r, n) ->
                                new Job(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getLong(4)),
                        Timestamp.from(now))
                .stream()
                .findFirst();
    }

    @Override
    public void defer(String id, Instant at) {
        jdbc.update(
                "UPDATE discovery.deletion_job SET attempts=attempts+1,next_attempt_at=? WHERE id=?",
                Timestamp.from(at),
                id);
    }

    @Override
    public void completed(String id) {
        jdbc.update("DELETE FROM discovery.deletion_job WHERE id=?", id);
    }

    @Override
    public void pruneRevocations(Instant now, int limit) {
        ShortTransactions.requireWrite();
        jdbc.update(
                "DELETE FROM discovery.subject_revocation WHERE credential_digest IN (SELECT credential_digest FROM discovery.subject_revocation WHERE expires_at<=? ORDER BY expires_at,credential_digest LIMIT ?)",
                Timestamp.from(now),
                limit);
    }
}
