package io.auralisy.publication.api;

import io.auralisy.session.api.SessionStore.Scope;
import java.util.Optional;

public interface RecoveryProjection {
    Optional<String> read(Scope scope);

    void save(Scope scope, String state);
}
