package io.auralisy.session.domain;

/** Only the empty-state display target; running recommendations use their published snapshot. */
public record SessionPolicy(int recommendationTargetCount) {
    public SessionPolicy {
        if (recommendationTargetCount < 1 || recommendationTargetCount > 1000)
            throw new IllegalArgumentException("INVALID_RECOMMENDATION_TARGET");
    }
}
