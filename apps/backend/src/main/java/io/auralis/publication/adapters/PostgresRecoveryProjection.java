package io.auralis.publication.adapters;

import io.auralis.persistence.PersistenceRejected;
import io.auralis.publication.api.RecoveryProjection;
import io.auralis.session.api.SessionStore;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresRecoveryProjection implements RecoveryProjection {
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;
    private final io.auralis.contracts.MessageDefinitions protocol;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final io.auralis.discovery.api.DiscoveryStore discovery;
    private final io.auralis.discovery.api.DispatchQueue queue;

    public PostgresRecoveryProjection(
            JdbcTemplate jdbc,
            SessionStore sessions,
            io.auralis.contracts.MessageDefinitions protocol,
            com.fasterxml.jackson.databind.ObjectMapper json,
            io.auralis.discovery.api.DiscoveryStore discovery,
            io.auralis.discovery.api.DispatchQueue queue) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.protocol = protocol;
        this.json = json;
        this.discovery = discovery;
        this.queue = queue;
    }

    @Override
    public Optional<String> read(SessionStore.Scope scope) {
        return jdbc
                .query(
                        "SELECT state::text FROM discovery.recovery_projection WHERE session_id=? AND revision=?",
                        (r, n) -> r.getString(1),
                        scope.sessionId(),
                        scope.revision())
                .stream()
                .findFirst()
                .map(value -> live(scope, value));
    }

    @Override
    public void save(SessionStore.Scope scope, String state) {
        try {
            var node = json.readTree(state);
            if (!protocol.validDefinition("ProductState", node)
                    || !scope.sessionId().equals(node.path("session_id").asText())
                    || scope.revision() != node.path("revision").asLong())
                throw new PersistenceRejected("INVALID_RECOVERY_PROJECTION");
        } catch (java.io.IOException invalid) {
            throw new PersistenceRejected("INVALID_RECOVERY_PROJECTION");
        }
        sessions.lockActive(scope);
        int n =
                jdbc.update(
                        "INSERT INTO discovery.recovery_projection VALUES (?,?,?::jsonb) ON CONFLICT(session_id) DO UPDATE SET revision=excluded.revision,state=excluded.state WHERE discovery.recovery_projection.revision<excluded.revision OR (discovery.recovery_projection.revision=excluded.revision AND discovery.recovery_projection.state=excluded.state)",
                        scope.sessionId(),
                        scope.revision(),
                        state);
        if (n != 1) throw new PersistenceRejected("PROJECTION_REVISION_CONFLICT");
    }

    private String live(SessionStore.Scope scope, String text) {
        try {
            var state = json.readTree(text);
            for (var slot : state.path("slots"))
                if (!slot.path("card").isNull()) {
                    String candidate = slot.path("card").path("candidate_id").asText();
                    var rows =
                            jdbc.queryForList(
                                    "SELECT l.state,l.generation_run FROM discovery.card_slot s JOIN discovery.long_review l ON l.round_id=s.round_id AND l.candidate_id=s.candidate_id WHERE s.session_id=? AND s.candidate_id=?",
                                    scope.sessionId(),
                                    candidate);
                    if (rows.size() == 1) {
                        var row = rows.getFirst();
                        String status = (String) row.get("state"),
                                run = (String) row.get("generation_run");
                        if (status.equals("GENERATING")
                                && !java.util.Set.of("WAITING", "DISPATCHING")
                                        .contains(queue.status(scope, run).status())
                                && !discovery.physicalWork(scope, run)) status = "INTERRUPTED";
                        ((com.fasterxml.jackson.databind.node.ObjectNode) slot.path("card"))
                                .put("long_review_state", status);
                    }
                }
            return state.toString();
        } catch (java.io.IOException e) {
            throw new PersistenceRejected("INVALID_RECOVERY_PROJECTION");
        }
    }
}
