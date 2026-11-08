package io.auralis.publication.api;

import io.auralis.session.api.SessionStore.Scope;
import java.util.Optional;

public interface RecoveryProjection {
    Optional<String> read(Scope scope);

    void save(Scope scope, String state);
}
