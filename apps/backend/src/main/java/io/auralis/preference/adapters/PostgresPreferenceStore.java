package io.auralis.preference.adapters;

import io.auralis.persistence.PersistenceRejected;
import io.auralis.preference.api.PreferenceStore;
import io.auralis.session.api.SessionStore;
import io.auralis.session.api.SessionStore.Scope;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresPreferenceStore implements PreferenceStore {
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;

    public PostgresPreferenceStore(JdbcTemplate jdbc, SessionStore sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Override
    public void save(
            Scope scope,
            String resolution,
            String input,
            String reference,
            String parser,
            String digest,
            String result) {
        sessions.lockActive(scope);
        jdbc.update(
                "INSERT INTO discovery.effective_preference VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT(session_id,resolution_id) DO NOTHING",
                scope.sessionId(),
                resolution,
                input,
                reference,
                parser,
                digest,
                result);
        var existing =
                jdbc.queryForMap(
                        "SELECT input_digest,reference_revision,parser_revision,effective_digest FROM discovery.effective_preference WHERE session_id=? AND resolution_id=?",
                        scope.sessionId(),
                        resolution);
        if (!input.equals(existing.get("input_digest"))
                || !reference.equals(existing.get("reference_revision"))
                || !parser.equals(existing.get("parser_revision"))
                || !digest.equals(existing.get("effective_digest")))
            throw new PersistenceRejected("IDEMPOTENCY_CONFLICT");
    }

    @Override
    public Optional<String> find(Scope scope, String resolution) {
        sessions.lockActive(scope);
        return jdbc
                .query(
                        "SELECT result::text FROM discovery.effective_preference WHERE session_id=? AND resolution_id=?",
                        (rs, n) -> rs.getString(1),
                        scope.sessionId(),
                        resolution)
                .stream()
                .findFirst();
    }
}
