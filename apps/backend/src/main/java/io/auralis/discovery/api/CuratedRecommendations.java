package io.auralis.discovery.api;

import io.auralis.session.api.SessionStore.Scope;

/** BE-001 supplies the fixed ordered list; no-positive input is not model success. */
public interface CuratedRecommendations {
    String candidates(Scope scope, String round, String effectivePreferences);
}
