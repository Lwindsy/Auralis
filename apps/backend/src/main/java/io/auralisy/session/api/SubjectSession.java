package io.auralisy.session.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SubjectSession {
    record Identity(String subjectId, long epoch, Instant expiresAt) {}

    record Bootstrap(String credential, String csrf, Instant expiresAt, String state) {
        @Override
        public String toString() {
            return "Bootstrap[redacted]";
        }
    }

    record Replacement(SessionStore.Scope scope, String previousSessionId, boolean replayed) {}

    Optional<Identity> authenticate(String credential);

    boolean validCsrf(String credential, String csrf);

    Bootstrap bootstrap(String credential);

    String restore(String credential, String sessionId);

    Replacement replace(
            String credential,
            String csrf,
            String commandId,
            String expectedSession,
            long expectedRevision,
            String input,
            String resolvedInput);

    Instant renew(String credential, String csrf, SessionStore.Scope scope);

    void heard(String credential, String csrf, SessionStore.Scope scope, UUID verifiedAlbum);

    void erase(String credential, String csrf);

    int cleanup(int limit);
}
