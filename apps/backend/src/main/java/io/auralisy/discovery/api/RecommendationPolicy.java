package io.auralisy.discovery.api;

/** Deployment-owned non-secret configuration. No implicit model or budget defaults. */
public interface RecommendationPolicy {
    String snapshot();
}
