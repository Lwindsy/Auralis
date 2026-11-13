package io.auralisy.bootstrap;

import io.auralisy.discovery.api.DispatchQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class DispatchConfiguration {
    @Bean
    io.auralisy.discovery.api.ExecutionPreparation executionPreparation(
            io.auralisy.discovery.application.Recommendations service) {
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
