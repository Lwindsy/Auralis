package io.auralis.discovery.adapters;

import io.auralis.discovery.api.SessionExecutions;
import io.auralis.persistence.ShortTransactions;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSessionExecutions implements SessionExecutions {
    private final JdbcTemplate jdbc;

    public PostgresSessionExecutions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void invalidate(List<String> sessions, String status) {
        ShortTransactions.requireWrite();
        if (!java.util.Set.of("SUPERSEDED", "CANCELLED", "EXPIRED").contains(status))
            throw new IllegalArgumentException("INVALID_TERMINAL_STATE");
        for (String id : sessions.stream().sorted().toList()) {
            jdbc.update(
                    "UPDATE discovery.discovery_intent SET status=?,revision=revision+1 WHERE session_id=? AND status IN ('WAITING','DISPATCHING','RUNNING')",
                    status,
                    id);
            jdbc.update(
                    "UPDATE discovery.execution SET cancellation_requested=true,revision=revision+1 WHERE session_id=? AND physical_released_at IS NULL",
                    id);
        }
    }

    @Override
    public boolean unreleased(List<String> sessions) {
        for (String id : sessions)
            if (jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.execution WHERE session_id=? AND physical_released_at IS NULL",
                            Long.class,
                            id)
                    > 0) return true;
        return false;
    }

    @Override
    public void invalidateSubject(String subject, String status) {
        ShortTransactions.requireWrite();
        if (!java.util.Set.of("CANCELLED", "EXPIRED").contains(status))
            throw new IllegalArgumentException("INVALID_TERMINAL_STATE");
        jdbc.update(
                "UPDATE discovery.discovery_intent SET status=?,revision=revision+1 WHERE subject_id=? AND status IN ('WAITING','DISPATCHING','RUNNING')",
                status,
                subject);
        jdbc.update(
                "UPDATE discovery.execution e SET cancellation_requested=true,revision=e.revision+1 FROM discovery.discovery_intent i WHERE e.run_id=i.id AND i.subject_id=? AND e.physical_released_at IS NULL",
                subject);
    }
}
