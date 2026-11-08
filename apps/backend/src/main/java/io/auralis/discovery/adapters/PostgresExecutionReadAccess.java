package io.auralis.discovery.adapters;

import io.auralis.discovery.api.ExecutionReadAccess;
import io.auralis.persistence.*;
import io.auralis.session.api.SessionStore.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresExecutionReadAccess implements ExecutionReadAccess {
    private final JdbcTemplate jdbc;

    public PostgresExecutionReadAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void guard(Scope s, String round, String execution, String coordinator, String python) {
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT coordinator_boot IS NOT NULL AND (maintenance OR coordinator_boot<>? OR python_boot<>?) FROM discovery.runtime_control WHERE singleton",
                        Boolean.class,
                        coordinator,
                        python))) throw new PersistenceRejected("EXECUTION_GONE");
        LockOrder.before(LockOrder.Level.ROUND, round);
        var rounds =
                jdbc.queryForList(
                        "SELECT id FROM discovery.recommendation_round WHERE id=? AND session_id=? AND deadline>clock_timestamp() FOR UPDATE",
                        String.class,
                        round,
                        s.sessionId());
        if (rounds.size() != 1) throw new PersistenceRejected("EXECUTION_GONE");
        LockOrder.before(LockOrder.Level.EXECUTION, execution);
        var found =
                jdbc.queryForList(
                        "SELECT e.id FROM discovery.execution e JOIN discovery.discovery_intent i ON i.id=e.run_id WHERE e.id=? AND e.round_id=? AND e.session_id=? AND e.coordinator_boot=? AND e.python_boot=? AND e.state IN ('RESERVED','ACCEPTED','RUNNING') AND e.physical_released_at IS NULL AND NOT e.cancellation_requested AND (i.technical_deadline IS NULL OR i.technical_deadline>clock_timestamp()) FOR UPDATE OF e",
                        String.class,
                        execution,
                        round,
                        s.sessionId(),
                        coordinator,
                        python);
        if (found.size() != 1) throw new PersistenceRejected("EXECUTION_GONE");
    }
}
