package io.auralisy.budget.api;

import io.auralisy.evidence.api.EvidenceAuthority;
import java.util.Map;

/** Trusted coordinator input, never constructed from a caller's grant payload. */
public interface BudgetAuthority {
    record Price(
            String revision,
            long input,
            long cachedInput,
            long output,
            long search,
            long maxInput,
            long maxOutput) {}

    record Context(
            EvidenceAuthority.Context evidence,
            String run,
            String sessionScope,
            String roundScope,
            long sessionCap,
            long roundCap,
            long foregroundCap,
            long backgroundCap,
            int modelCalls,
            int searchCalls,
            Map<String, Price> prices) {
        public Context {
            prices = Map.copyOf(prices);
        }
    }

    /** Idempotent registration; an existing scope cannot be reset or silently reconfigured. */
    void register(Context context);

    String grants(Context context, String request);

    /** Retained execution identity allows financial-only settlement after content deletion. */
    String settle(String execution, String request);

    String state(Context context);

    /** Verify a completed, non-truncated call in this round's authoritative ledger. */
    boolean completed(Context context, String invocation);

    boolean completed(Context context, String invocation, java.util.Set<String> purposes);
}
