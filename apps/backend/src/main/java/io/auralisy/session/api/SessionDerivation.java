package io.auralisy.session.api;

/** Participates in the new-session transaction after both session locks and target creation. */
public interface SessionDerivation {
    void derive(String sourceSession, SessionStore.Scope target, String rawInput);
}
