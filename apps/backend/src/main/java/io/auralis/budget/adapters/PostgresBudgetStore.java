package io.auralis.budget.adapters;

import io.auralis.budget.api.BudgetStore;
import io.auralis.persistence.LockOrder;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.persistence.ShortTransactions;
import io.auralis.session.api.SessionStore;
import io.auralis.session.api.SessionStore.Scope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresBudgetStore implements BudgetStore {
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;

    public PostgresBudgetStore(JdbcTemplate jdbc, SessionStore sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Override
    public void createScope(Scope session, String id, String parent, long cap, String price) {
        sessions.lockActive(session);
        jdbc.update(
                "INSERT INTO discovery.budget_scope(id,session_id,parent_scope_id,cap_microusd,price_revision) VALUES (?,?,?,?,?)",
                id,
                session.sessionId(),
                parent,
                cap,
                price);
    }

    @Override
    public List<Totals> lockScopes(Scope session, List<String> ids) {
        sessions.lockActive(session);
        var values = new ArrayList<Totals>();
        for (String id : ids.stream().distinct().sorted().toList()) {
            LockOrder.before(LockOrder.Level.BUDGET, id);
            values.add(
                    jdbc.queryForObject(
                            "SELECT cap_microusd,reserved_microusd,settled_microusd,revision FROM discovery.budget_scope WHERE id=? AND session_id=? FOR UPDATE",
                            (rs, n) ->
                                    new Totals(
                                            rs.getLong(1),
                                            rs.getLong(2),
                                            rs.getLong(3),
                                            rs.getLong(4)),
                            id,
                            session.sessionId()));
        }
        return List.copyOf(values);
    }

    @Override
    public boolean reserve(Scope session, String id, long revision, long amount) {
        if (amount < 0) throw new IllegalArgumentException("NEGATIVE_RESERVATION");
        lockScopes(session, List.of(id));
        // Subtraction avoids bigint overflow; a recorded overrun prohibits additional reservation.
        return jdbc.update(
                        "UPDATE discovery.budget_scope SET reserved_microusd=reserved_microusd+?,revision=revision+1 WHERE id=? AND session_id=? AND revision=? AND settled_microusd<=cap_microusd AND reserved_microusd<=cap_microusd-settled_microusd AND ?<=cap_microusd-settled_microusd-reserved_microusd",
                        amount,
                        id,
                        session.sessionId(),
                        revision,
                        amount)
                == 1;
    }

    @Override
    public void createInvocation(
            Scope session,
            String id,
            String round,
            String execution,
            String budget,
            String lane,
            String purpose,
            String digest,
            long amount,
            String price) {
        lockScopes(session, List.of(budget));
        LockOrder.before(LockOrder.Level.INVOCATION, id);
        jdbc.update(
                "INSERT INTO discovery.invocation(id,session_id,round_id,execution_id,scope_id,lane,purpose,request_digest,reserved_microusd,outcome,price_binding) VALUES (?,?,?,?,?,?,?,?,?,'RESERVED',?::jsonb)",
                id,
                session.sessionId(),
                round,
                execution,
                budget,
                lane,
                purpose,
                digest,
                amount,
                price);
    }

    @Override
    public boolean consumeSend(Scope session, String id) {
        sessions.lockActive(session);
        LockOrder.before(LockOrder.Level.INVOCATION, id);
        return jdbc.update(
                        "UPDATE discovery.invocation SET send_consumed=true WHERE id=? AND session_id=? AND send_consumed=false AND outcome='RESERVED'",
                        id,
                        session.sessionId())
                == 1;
    }

    @Override
    public void recordUsage(
            String invocation,
            String receipt,
            String digest,
            long seq,
            Long cost,
            Long input,
            Long output,
            String outcome) {
        ShortTransactions.requireWrite();
        LockOrder.before(LockOrder.Level.INVOCATION, invocation);
        jdbc.queryForList("SELECT id FROM discovery.invocation WHERE id=? FOR UPDATE", invocation);
        jdbc.update(
                "INSERT INTO discovery.usage_receipt(invocation_id,receipt_id,payload_digest,settlement_seq,cost_microusd,input_tokens,output_tokens,outcome) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(invocation_id,receipt_id) DO NOTHING",
                invocation,
                receipt,
                digest,
                seq,
                cost,
                input,
                output,
                outcome);
        String existing =
                jdbc.queryForObject(
                        "SELECT payload_digest FROM discovery.usage_receipt WHERE invocation_id=? AND receipt_id=?",
                        String.class,
                        invocation,
                        receipt);
        if (!digest.equals(existing)) throw new PersistenceRejected("IDEMPOTENCY_CONFLICT");
    }
}
