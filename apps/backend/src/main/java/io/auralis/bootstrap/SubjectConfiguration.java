package io.auralis.bootstrap;

import io.auralis.session.domain.SubjectCredentials;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SubjectConfiguration {
    @Bean
    io.auralis.session.domain.SessionPolicy sessionPolicy(OnlineProperties properties) {
        return new io.auralis.session.domain.SessionPolicy(
                properties.getRecommendationTargetCount());
    }

    @Bean
    SubjectCredentials subjectCredentials(OnlineProperties properties, WireProtocol protocol) {
        properties.validate(protocol.revision());
        return new SubjectCredentials(properties.getSubjectSecret());
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock sessionClock() {
        return Clock.systemUTC();
    }
}
