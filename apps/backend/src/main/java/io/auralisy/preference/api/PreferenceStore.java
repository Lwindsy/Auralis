package io.auralisy.preference.api;

import io.auralisy.session.api.SessionStore.Scope;
import java.util.Optional;

public interface PreferenceStore {
    void save(
            Scope scope,
            String resolution,
            String inputDigest,
            String referenceRevision,
            String parserRevision,
            String effectiveDigest,
            String result);

    Optional<String> find(Scope scope, String resolution);
}
