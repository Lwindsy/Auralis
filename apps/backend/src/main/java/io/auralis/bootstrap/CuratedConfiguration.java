package io.auralis.bootstrap;

import io.auralis.discovery.api.CuratedRecommendations;
import io.auralis.persistence.PersistenceRejected;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class CuratedConfiguration {
    @Bean
    @ConditionalOnMissingBean(CuratedRecommendations.class)
    CuratedRecommendations unavailableCuratedList() {
        return (scope, round, preference) -> {
            throw new PersistenceRejected("CURATED_UNAVAILABLE");
        };
    }
}
