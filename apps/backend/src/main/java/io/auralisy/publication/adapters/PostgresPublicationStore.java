package io.auralisy.publication.adapters;

import io.auralisy.persistence.LockOrder;
import io.auralisy.persistence.PersistenceRejected;
import io.auralisy.publication.api.PublicationStore;
import io.auralisy.session.api.SessionStore;
import io.auralisy.session.api.SessionStore.Scope;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresPublicationStore implements PublicationStore {
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;

    public PostgresPublicationStore(JdbcTemplate jdbc, SessionStore sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Override
    public void addCandidate(Scope scope, Candidate c) {
        sessions.lockActive(scope);
        jdbc.update(
                "INSERT INTO discovery.round_candidate(session_id,round_id,candidate_id,release_group_id,rank,identity_binding,audience_binding,disposition) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?)",
                scope.sessionId(),
                c.round(),
                c.id(),
                c.album(),
                c.rank(),
                c.identity(),
                c.audience(),
                c.disposition());
    }

    @Override
    public List<ReadyCandidate> candidates(
            Scope scope, String round, String disposition, int limit) {
        sessions.lockActive(scope);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("INVALID_PAGE_LIMIT");
        return jdbc.query(
                "SELECT candidate_id,release_group_id,rank FROM discovery.round_candidate WHERE session_id=? AND round_id=? AND disposition=? ORDER BY rank,candidate_id LIMIT ?",
                (rs, n) ->
                        new ReadyCandidate(
                                rs.getString(1), rs.getObject(2, UUID.class), rs.getInt(3)),
                scope.sessionId(),
                round,
                disposition,
                limit);
    }

    @Override
    public void saveCard(
            Scope scope,
            String round,
            String candidate,
            String content,
            String basis,
            String state) {
        sessions.lockActive(scope);
        jdbc.update(
                "INSERT INTO discovery.recommendation_card VALUES (?,?,?,?::jsonb,?,?)",
                scope.sessionId(),
                round,
                candidate,
                content,
                basis,
                state);
    }

    @Override
    public void saveDelivery(
            Scope scope,
            String id,
            String round,
            String execution,
            long seq,
            String digest,
            String result) {
        sessions.lockActive(scope);
        LockOrder.before(LockOrder.Level.DELIVERY, id);
        jdbc.update(
                "INSERT INTO discovery.delivery_receipt VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO NOTHING",
                id,
                scope.sessionId(),
                round,
                execution,
                seq,
                digest,
                result);
        Integer same =
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.delivery_receipt WHERE id=? AND session_id=? AND round_id=? AND execution_id=? AND seq=? AND payload_digest=?",
                        Integer.class,
                        id,
                        scope.sessionId(),
                        round,
                        execution,
                        seq,
                        digest);
        if (same == null || same != 1) throw new PersistenceRejected("IDEMPOTENCY_CONFLICT");
    }

    @Override
    public boolean putSlot(
            Scope scope, String slot, String round, String candidate, String state, long expected) {
        sessions.lockActive(scope);
        LockOrder.before(LockOrder.Level.SLOT, slot);
        if (expected == -1)
            return jdbc.update(
                            "INSERT INTO discovery.card_slot(session_id,slot_id,round_id,candidate_id,state) VALUES (?,?,?,?,?) ON CONFLICT(session_id,slot_id) DO NOTHING",
                            scope.sessionId(),
                            slot,
                            round,
                            candidate,
                            state)
                    == 1;
        return jdbc.update(
                        "UPDATE discovery.card_slot SET round_id=?,candidate_id=?,state=?,revision=revision+1 WHERE session_id=? AND slot_id=? AND revision=?",
                        round,
                        candidate,
                        state,
                        scope.sessionId(),
                        slot,
                        expected)
                == 1;
    }

    @Override
    public boolean putLongReview(
            Scope scope,
            String round,
            String candidate,
            String generation,
            String state,
            String content,
            String reason,
            long expected) {
        sessions.lockActive(scope);
        if (expected == -1)
            return jdbc.update(
                            "INSERT INTO discovery.long_review(session_id,round_id,candidate_id,generation_run,state,content,reason) VALUES (?,?,?,?,?,?::jsonb,?) ON CONFLICT(round_id,candidate_id) DO NOTHING",
                            scope.sessionId(),
                            round,
                            candidate,
                            generation,
                            state,
                            content,
                            reason)
                    == 1;
        return jdbc.update(
                        "UPDATE discovery.long_review SET generation_run=?,state=?,content=?::jsonb,reason=?,revision=revision+1 WHERE session_id=? AND round_id=? AND candidate_id=? AND revision=?",
                        generation,
                        state,
                        content,
                        reason,
                        scope.sessionId(),
                        round,
                        candidate,
                        expected)
                == 1;
    }
}
