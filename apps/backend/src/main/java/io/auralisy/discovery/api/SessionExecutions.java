package io.auralisy.discovery.api;

import java.util.List;

/** Lifecycle handoff. BE-010 consumes cancellation requests and proves physical release. */
public interface SessionExecutions {
    void invalidate(List<String> sessions, String terminalStatus);

    void invalidateSubject(String subjectId, String terminalStatus);

    boolean unreleased(List<String> sessions);
}
