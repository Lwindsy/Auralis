package io.auralis.budget.api;

import io.auralis.session.api.SessionStore.Scope;
import java.util.List;

public interface BudgetStore {
    record Totals(long cap, long reserved, long settled, long revision) {}

    void createScope(Scope session, String id, String parent, long cap, String priceRevision);

    List<Totals> lockScopes(Scope session, List<String> ids);

    boolean reserve(Scope session, String id, long expectedRevision, long amount);

    void createInvocation(
            Scope session,
            String id,
            String round,
            String execution,
            String budgetScope,
            String lane,
            String purpose,
            String digest,
            long reserved,
            String priceBinding);

    boolean consumeSend(Scope session, String invocation);

    void recordUsage(
            String invocation,
            String receipt,
            String digest,
            long sequence,
            Long cost,
            Long inputTokens,
            Long outputTokens,
            String outcome);
}
