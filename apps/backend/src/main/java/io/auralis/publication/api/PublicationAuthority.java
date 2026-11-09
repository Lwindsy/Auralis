package io.auralis.publication.api;

import io.auralis.catalog.api.AudienceQuery;
import io.auralis.discovery.api.DispatchQueue;
import io.auralis.session.api.SessionStore.Scope;
import java.util.Set;

/** Internal trusted composition; candidate bundles originate from CatalogValidation. */
public interface PublicationAuthority {
    record Context(
            DispatchQueue.Context execution,
            String preferenceResolution,
            AudienceQuery.Binding audience,
            String policy,
            int target) {}

    @FunctionalInterface
    interface Supply {
        String enqueue(Scope scope, String round, Set<String> slots);
    }

    String begin(Scope scope, String round, String run, int target, String status);

    void register(Context context, String bundle);

    String deliver(Context context, String delivery);

    String state(Scope scope, String round);

    String operate(
            Scope scope,
            String round,
            String command,
            String operation,
            String slot,
            Supply supply);

    String longReview(Scope scope, String round, String candidate);

    String regenerate(Scope scope, String round, String candidate, Supply supply);

    String bundle(Scope scope, String round);

    Context context(DispatchQueue.Context execution);

    String exclusions(Scope scope);

    String continuation(
            Scope scope,
            String round,
            String operation,
            Set<String> targets,
            String exclusionDigest);

    void bindRun(Scope scope, String round, String run, String bundle);

    String waitFor(Scope scope, String round, String run);

    String fail(Scope scope, String round, String reason);

    String displayState(Scope scope, String round);

    String candidateRound(Scope scope, String candidate);

    String exclusions(Scope scope, String currentRound);

    void scheduleLongs(Scope scope, String round, Set<String> candidates, Supply supply);
}
