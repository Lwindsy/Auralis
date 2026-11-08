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
                        "SELECT id FROM discovery.execution WHERE id=? AND round_id=? AND session_id=? AND coordinator_boot=? AND python_boot=? AND state IN ('RESERVED','ACCEPTED','RUNNING') AND physical_released_at IS NULL AND NOT cancellation_requested FOR UPDATE",
                        String.class,
                        execution,
                        round,
                        s.sessionId(),
                        coordinator,
                        python);
        if (found.size() != 1) throw new PersistenceRejected("EXECUTION_GONE");
    }
}
