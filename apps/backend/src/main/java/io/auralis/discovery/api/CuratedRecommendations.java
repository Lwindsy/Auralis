package io.auralis.discovery.api;

import io.auralis.session.api.SessionStore.Scope;
import java.util.List;

/** BE-001 supplies the fixed ordered list; no-positive input is not model success. */
public interface CuratedRecommendations {
    record Item(
            String id, String identity, String shortReview, String longReview, String provenance) {}

    record Batch(String revision, List<Item> items, String diagnostics) {
        public Batch {
            items = List.copyOf(items);
        }
    }

    Batch candidates(Scope scope, String round, String effectivePreferences);
}
