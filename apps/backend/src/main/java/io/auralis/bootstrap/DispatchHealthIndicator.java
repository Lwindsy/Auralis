package io.auralis.bootstrap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness includes recovery and coordinator ownership, not merely a reachable peer. */
@Component("dispatchHealthIndicator")
public final class DispatchHealthIndicator implements HealthIndicator {
    private final ObjectProvider<DispatchLifecycle> lifecycle;

    public DispatchHealthIndicator(ObjectProvider<DispatchLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public Health health() {
        var dispatch = lifecycle.getIfAvailable();
        return dispatch != null && dispatch.ready() ? Health.up().build() : Health.down().build();
    }
}
