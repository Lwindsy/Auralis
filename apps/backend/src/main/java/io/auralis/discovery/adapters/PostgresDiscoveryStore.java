package io.auralis.discovery.adapters;

import io.auralis.discovery.api.DiscoveryStore;
import io.auralis.persistence.LockOrder;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.session.api.SessionStore;
import io.auralis.session.api.SessionStore.Scope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresDiscoveryStore implements DiscoveryStore {
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;

    public PostgresDiscoveryStore(JdbcTemplate jdbc, SessionStore sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Override
    public void lockRuntime() {
        LockOrder.before(LockOrder.Level.RUNTIME, "singleton");
        jdbc.queryForObject(
                "SELECT singleton FROM discovery.runtime_control WHERE singleton=true FOR UPDATE",
                Boolean.class);
    }

    private void round(Scope scope, String round) {
        sessions.lockActive(scope);
        LockOrder.before(LockOrder.Level.ROUND, round);
        var rows =
                jdbc.queryForList(
                        "SELECT id FROM discovery.recommendation_round WHERE id=? AND session_id=? FOR UPDATE",
                        round,
                        scope.sessionId());
        if (rows.isEmpty()) throw new PersistenceRejected("ROUND_UNAVAILABLE");
    }

    @Override
    public void createRound(
            Scope scope, String id, int sequence, String config, String binding, Instant deadline) {
        sessions.lockActive(scope);
        jdbc.update(
                "INSERT INTO discovery.recommendation_round(id,session_id,round_seq,configuration,recall_binding,state,deadline) VALUES (?,?,?,?::jsonb,?::jsonb,'CREATED',?)",
                id,
                scope.sessionId(),
                sequence,
                config,
                binding,
                Timestamp.from(deadline));
    }

    @Override
    public void createIntent(
            Scope scope,
            String round,
            String run,
            String operation,
            long inputRevision,
            String digest,
            Instant accepted,
            Instant deadline) {
        lockRuntime();
        round(scope, round);
        jdbc.update(
                "INSERT INTO discovery.discovery_intent(id,subject_id,session_id,round_id,operation,input_revision,request_digest,accepted_at,queue_deadline,status) VALUES (?,?,?,?,?,?,?,?,?,'WAITING')",
                run,
                scope.subjectId(),
                scope.sessionId(),
                round,
                operation,
                inputRevision,
                digest,
                Timestamp.from(accepted),
                Timestamp.from(deadline));
    }

    @Override
    public List<Waiting> waiting(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("INVALID_PAGE_LIMIT");
        return jdbc.query(
                "SELECT id,session_id,subject_id,accepted_at,queue_deadline FROM discovery.discovery_intent WHERE status='WAITING' ORDER BY accepted_at,id LIMIT ?",
                (rs, n) ->
                        new Waiting(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getTimestamp(4).toInstant(),
                                rs.getTimestamp(5).toInstant()),
                limit);
    }

    @Override
    public void reserveExecution(
            Scope scope,
            String round,
            String run,
            String execution,
            int slot,
            String coordinator,
            String python) {
        lockRuntime();
        round(scope, round);
        LockOrder.before(LockOrder.Level.EXECUTION, execution);
        jdbc.update(
                "INSERT INTO discovery.execution(id,session_id,round_id,run_id,coordinator_boot,python_boot,slot_no,state) VALUES (?,?,?,?,?,?,?,'RESERVED')",
                execution,
                scope.sessionId(),
                round,
                run,
                coordinator,
                python,
                slot);
        int updated =
                jdbc.update(
                        "UPDATE discovery.execution_slot SET state='RESERVED',execution_id=? WHERE slot_no=? AND state='FREE' AND execution_id IS NULL",
                        execution,
                        slot);
        if (updated != 1) throw new PersistenceRejected("SLOT_UNAVAILABLE");
    }

    @Override
    public boolean transitionRun(
            Scope scope, String run, long revision, String expected, String next) {
        lockRuntime();
        sessions.lockActive(scope);
        return jdbc.update(
                        "UPDATE discovery.discovery_intent SET status=?,revision=revision+1 WHERE id=? AND session_id=? AND status=? AND revision=?",
                        next,
                        run,
                        scope.sessionId(),
                        expected,
                        revision)
                == 1;
    }

    @Override
    public boolean releaseExecution(
            Scope scope, String execution, String coordinator, String python, Instant at) {
        lockRuntime();
        // Cleanup is permitted after a subject/session barrier; it never writes content.
        LockOrder.before(LockOrder.Level.EXECUTION, execution);
        var rows =
                jdbc.queryForList(
                        "SELECT slot_no FROM discovery.execution WHERE id=? AND session_id=? AND coordinator_boot=? AND python_boot=? AND physical_released_at IS NULL FOR UPDATE",
                        execution,
                        scope.sessionId(),
                        coordinator,
                        python);
        if (rows.isEmpty()) return false;
        jdbc.update(
                "UPDATE discovery.execution SET state='CLOSED',physical_released_at=?,revision=revision+1 WHERE id=?",
                Timestamp.from(at),
                execution);
        if (jdbc.update(
                        "UPDATE discovery.execution_slot SET state='FREE',execution_id=NULL WHERE execution_id=?",
                        execution)
                != 1) throw new PersistenceRejected("SLOT_BINDING_MISMATCH");
        return true;
    }

    @Override
    public java.util.Optional<Round> latestRound(Scope scope) {
        sessions.lockActive(scope);
        return jdbc
                .query(
                        "SELECT id,round_seq,configuration::text,deadline FROM discovery.recommendation_round WHERE session_id=? ORDER BY round_seq DESC LIMIT 1",
                        (r, n) ->
                                new Round(
                                        r.getString(1),
                                        r.getInt(2),
                                        r.getString(3),
                                        r.getTimestamp(4).toInstant()),
                        scope.sessionId())
                .stream()
                .findFirst();
    }

    @Override
    public Round roundInfo(Scope scope, String round) {
        sessions.lockActive(scope);
        var rows =
                jdbc.query(
                        "SELECT id,round_seq,configuration::text,deadline FROM discovery.recommendation_round WHERE session_id=? AND id=?",
                        (r, n) ->
                                new Round(
                                        r.getString(1),
                                        r.getInt(2),
                                        r.getString(3),
                                        r.getTimestamp(4).toInstant()),
                        scope.sessionId(),
                        round);
        if (rows.size() != 1) throw new PersistenceRejected("RESOURCE_UNAVAILABLE");
        return rows.getFirst();
    }

    @Override
    public void configureRound(Scope scope, String round, String config) {
        sessions.lockActive(scope);
        jdbc.update(
                "UPDATE discovery.recommendation_round SET configuration=?::jsonb,revision=revision+1 WHERE id=? AND session_id=?",
                config,
                round,
                scope.sessionId());
    }

    @Override
    public String runRound(Scope scope, String run) {
        sessions.lockActive(scope);
        var rows =
                jdbc.queryForList(
                        "SELECT round_id FROM discovery.discovery_intent WHERE id=? AND session_id=?",
                        String.class,
                        run,
                        scope.sessionId());
        if (rows.size() != 1) throw new PersistenceRejected("RESOURCE_UNAVAILABLE");
        return rows.getFirst();
    }

    @Override
    public boolean physicalWork(Scope scope, String run) {
        sessions.lockActive(scope);
        return jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.execution WHERE session_id=? AND run_id=? AND physical_released_at IS NULL",
                        Long.class,
                        scope.sessionId(),
                        run)
                > 0;
    }

    @Override
    public void extendDeadline(Scope scope, String round, Instant deadline) {
        sessions.lockActive(scope);
        jdbc.update(
                "UPDATE discovery.recommendation_round SET deadline=greatest(deadline,?),revision=revision+1 WHERE id=? AND session_id=?",
                Timestamp.from(deadline),
                round,
                scope.sessionId());
    }
}
