package io.auralis.bootstrap;

import io.auralis.discovery.api.DispatchQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class DispatchConfiguration {
    @Bean
    io.auralis.discovery.api.ExecutionPreparation executionPreparation(
            io.auralis.discovery.application.Recommendations service) {
        return service::prepareExecution;
    }

    @Bean
    DispatchQueue.Limits dispatchLimits(
            @Value("${auralis.dispatch-executions:3}") int executions,
            @Value("${auralis.dispatch-waiting:5}") int waiting,
            @Value("${auralis.dispatch-queue-seconds:30}") int queueSeconds,
            @Value("${auralis.dispatch-execution-seconds:120}") int executionSeconds) {
        return new DispatchQueue.Limits(executions, waiting, queueSeconds, executionSeconds);
    }
}
