package io.auralis.bootstrap;

import io.auralis.discovery.api.*;
import io.auralis.discovery.application.RoundDispatcher;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicitly enable once product composition supplies trusted dispatch plans and prices. */
@Component
@ConditionalOnProperty(name = "auralis.dispatch-enabled", havingValue = "true")
public final class DispatchLifecycle implements AutoCloseable {
    private final Connection owner;
    private final ScheduledExecutorService worker;
    private final DispatchQueue queue;
    private final String boot = UUID.randomUUID().toString();
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    public DispatchLifecycle(
            DataSource source,
            DispatchQueue queue,
            PythonClient peer,
            Clock clock,
            DispatchQueue.Limits limits)
            throws Exception {
        this(
                source,
                queue,
                peer,
                clock,
                limits.executions(),
                limits.waiting(),
                limits.queueSeconds(),
                limits.executionSeconds());
    }

    public DispatchLifecycle(
            DataSource source,
            DispatchQueue queue,
            PythonClient peer,
            Clock clock,
            @Value("${auralis.dispatch-executions:3}") int executions,
            @Value("${auralis.dispatch-waiting:5}") int waiting,
            @Value("${auralis.dispatch-queue-seconds:30}") int queueSeconds,
            @Value("${auralis.dispatch-execution-seconds:120}") int executionSeconds)
            throws Exception {
        this.queue = queue;
        var limits = new DispatchQueue.Limits(executions, waiting, queueSeconds, executionSeconds);
        owner = source.getConnection();
        try (var statement = owner.createStatement();
                var rows = statement.executeQuery("SELECT pg_try_advisory_lock(1096110668)")) {
            if (!rows.next() || !rows.getBoolean(1))
                throw new IllegalStateException("COORDINATOR_ALREADY_RUNNING");
        } catch (Exception e) {
            owner.close();
            throw e;
        }
        RoundDispatcher dispatcher;
        try {
            dispatcher = new RoundDispatcher(queue, peer, limits, boot, clock);
        } catch (RuntimeException failure) {
            try (var statement = owner.createStatement()) {
                statement.execute("SELECT pg_advisory_unlock(1096110668)");
            } finally {
                owner.close();
            }
            throw failure;
        }
        worker =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var t = new Thread(r, "auralis-dispatch");
                            t.setDaemon(true);
                            return t;
                        });
        worker.scheduleWithFixedDelay(
                () -> {
                    try {
                        if (!owner.isValid(1)) {
                            queue.unavailable(boot);
                            return;
                        }
                        dispatcher.tick();
                    } catch (Exception e) {
                        try {
                            queue.unavailable(boot);
                        } catch (RuntimeException unavailable) {
                            /* Retry on the next bounded tick. */
                        }
                    }
                },
                0,
                200,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow();
        worker.awaitTermination(5, TimeUnit.SECONDS);
        try {
            queue.unavailable(boot);
            try (var statement = owner.createStatement()) {
                statement.execute("SELECT pg_advisory_unlock(1096110668)");
            }
        } finally {
            owner.close();
        }
    }
}
