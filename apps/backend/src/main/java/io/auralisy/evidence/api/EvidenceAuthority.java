package io.auralisy.evidence.api;

import io.auralisy.session.api.SessionStore.Scope;
import java.util.Map;

/** Internal coordinator context; never deserialize a Python/browser request into it. */
public interface EvidenceAuthority {
    record Context(
            Scope scope,
            String round,
            String execution,
            String coordinatorBoot,
            String pythonBoot,
            long catalogDataset,
            int readLimit,
            Map<String, String> candidates,
            boolean allowPrepared) {
        public Context {
            candidates = Map.copyOf(candidates);
        }
    }

    String authorize(Context context, String request);

    String receipts(Context context, String request);

    String seeds(Context context);

    String saveSeeds(Context context, String request);

    boolean usableRead(Context context, String readId, String candidateId, boolean seed);

    String references(Scope scope, String round, String candidate);
}
