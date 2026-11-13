package io.auralisy.discovery.api;

import io.auralisy.budget.api.BudgetAuthority;
import io.auralisy.evidence.api.EvidenceAuthority;
import io.auralisy.session.api.SessionStore.Scope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trusted Java product composition port; never bind browser/Python JSON directly to Request. */
public interface DispatchQueue {
    record Limits(int executions, int waiting, int queueSeconds, int executionSeconds) {
        public Limits(int waiting, int queueSeconds, int executionSeconds) {
            this(3, waiting, queueSeconds, executionSeconds);
        }

        public Limits {
            if (executions < 1
                    || executions > 3
                    || waiting < 1
                    || waiting > 5
                    || queueSeconds < 1
                    || queueSeconds > 30
                    || executionSeconds < 1
                    || executionSeconds > 180
                    || queueSeconds > executionSeconds)
                throw new IllegalArgumentException("INVALID_DISPATCH_LIMITS");
        }
    }

    record Request(
            Scope scope,
            String round,
            String operation,
            String command,
            String template,
            Map<String, BudgetAuthority.Price> prices,
            long catalogDataset,
            Map<String, String> candidates,
            Set<String> demands,
            Instant originalAcceptedAt) {
        public Request(
                Scope scope,
                String round,
                String operation,
                String command,
                String template,
                Map<String, BudgetAuthority.Price> prices,
                long catalogDataset,
                Map<String, String> candidates,
                Set<String> demands) {
            this(
                    scope,
                    round,
                    operation,
                    command,
                    template,
                    prices,
                    catalogDataset,
                    candidates,
                    demands,
                    null);
        }

        public Request {
            prices = Map.copyOf(prices);
            candidates = Map.copyOf(candidates);
            demands = Set.copyOf(demands);
        }
    }

    record Accepted(
            String run,
            String status,
            Instant acceptedAt,
            Instant queueDeadline,
            String errorCode) {}

    record Job(
            String execution,
            String round,
            String run,
            Scope scope,
            String coordinatorBoot,
            String pythonBoot,
            String payload,
            int attempts,
            boolean cancel,
            Instant deadline) {}

    record Context(EvidenceAuthority.Context evidence, BudgetAuthority.Context budget) {}

    Accepted accept(Request request, Limits limits);

    /** Invoked by the single dispatcher after a validated RuntimeView. */
    void reconcile(String coordinatorBoot, String runtime);

    void beginRecovery(String coordinatorBoot);

    void unavailable(String coordinatorBoot);

    List<Job> active();

    boolean ready(String coordinatorBoot);

    Job claim(String coordinatorBoot, Limits limits);

    boolean markSending(Job job);

    void observe(Job job, String executionView);

    void stop(Job job, String runStatus);

    void release(Job job, String runStatus);

    void cancel(Scope scope, String run);

    Context context(String execution);

    Accepted status(Scope scope, String run);

    Set<String> demands(Scope scope, String run);

    /** Publication decides the semantic outcome before physical execution cleanup. */
    void complete(Scope scope, String run, String outcome);

    String startPayload(String execution);

    void bindCandidates(String execution, Map<String, String> verifiedCandidates);

    record Command(String id, String execution, String payload) {}

    void prioritize(Scope scope, String run, String candidate, String command);

    List<Command> commands();

    void acknowledge(Command command);

    void display(Scope scope, String round, String state, String command);

    java.util.Optional<Accepted> mergeSupply(
            Scope scope, String round, String command, Set<String> demands, Limits limits);

    boolean canAccept(Scope scope, Limits limits);
}
