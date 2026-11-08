package io.auralis.discovery.api;

import io.auralis.session.api.SessionStore.Scope;
import java.time.Instant;
import java.util.List;

public interface DiscoveryStore {
    record Waiting(
            String runId,
            String sessionId,
            String subjectId,
            Instant acceptedAt,
            Instant deadline) {}

    void lockRuntime();

    void createRound(
            Scope scope,
            String id,
            int sequence,
            String configuration,
            String recallBinding,
            Instant deadline);

    void createIntent(
            Scope scope,
            String round,
            String run,
            String operation,
            long inputRevision,
            String digest,
            Instant accepted,
            Instant deadline);

    List<Waiting> waiting(int limit);

    void reserveExecution(
            Scope scope,
            String round,
            String run,
            String execution,
            int slot,
            String coordinatorBoot,
            String pythonBoot);

    boolean transitionRun(Scope scope, String run, long revision, String expected, String next);

    boolean releaseExecution(
            Scope scope,
            String execution,
            String coordinatorBoot,
            String pythonBoot,
            Instant physicalRelease);
}
