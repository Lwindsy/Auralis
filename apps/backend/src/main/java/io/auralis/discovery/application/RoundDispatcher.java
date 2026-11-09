package io.auralis.discovery.application;

import static io.auralis.contracts.CanonicalJson.JSON;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralis.discovery.api.*;
import io.auralis.persistence.ShortTransactions;
import java.time.Clock;
import java.time.Instant;

/** One coordinator loop. Network calls never participate in a database transaction. */
public final class RoundDispatcher {
    private final DispatchQueue queue;
    private final PythonClient python;
    private final DispatchQueue.Limits limits;
    private final String boot;
    private final Clock clock;

    public RoundDispatcher(
            DispatchQueue queue,
            PythonClient python,
            DispatchQueue.Limits limits,
            String boot,
            Clock clock) {
        this.queue = queue;
        this.python = python;
        this.limits = limits;
        this.boot = boot;
        this.clock = clock;
        queue.beginRecovery(boot);
    }

    private JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new PythonClient.Unavailable();
        }
    }

    public synchronized void tick() {
        ShortTransactions.outsideTransaction(
                () -> {
                    try {
                        String runtime = python.runtime();
                        if (json(runtime).path("execution_capacity").asInt() != limits.executions())
                            throw new PythonClient.Unavailable();
                        queue.reconcile(boot, runtime);
                        for (var job : queue.active()) {
                            if (!job.coordinatorBoot().equals(boot)) queue.stop(job, "INTERRUPTED");
                            boolean stopping =
                                    job.cancel()
                                            || !job.coordinatorBoot().equals(boot)
                                            || !job.deadline().isAfter(clock.instant());
                            if (stopping && job.attempts() == 0) {
                                queue.release(job, "INTERRUPTED");
                                continue;
                            }
                            if (job.attempts() == 0) {
                                send(job);
                                continue;
                            }
                            JsonNode p = json(job.payload());
                            var admission =
                                    json(
                                            python.admission(
                                                    job.execution(),
                                                    job.pythonBoot(),
                                                    p.path("payload_digest").asText(),
                                                    job.deadline().toString()));
                            if (!job.execution().equals(admission.path("execution_id").asText())
                                    || !job.pythonBoot().equals(admission.path("boot_id").asText())
                                    || !p.path("payload_digest")
                                            .equals(admission.path("payload_digest")))
                                throw new PythonClient.Unavailable();
                            switch (admission.path("status").asText()) {
                                case "ACTIVE", "TERMINAL" -> {
                                    if (admission.path("execution").isNull())
                                        throw new PythonClient.Unavailable();
                                    if (stopping
                                            && !admission
                                                    .path("execution")
                                                    .path("slot_released")
                                                    .asBoolean())
                                        queue.observe(
                                                job,
                                                python.cancel(
                                                        job.execution(),
                                                        job.coordinatorBoot(),
                                                        job.pythonBoot(),
                                                        "USER_CANCELLED"));
                                    else queue.observe(job, admission.path("execution").toString());
                                }
                                case "NOT_ACCEPTED", "CLEARED" -> {
                                    String until = admission.path("dedup_until").asText();
                                    if (until.isEmpty()
                                            || !Instant.parse(until).equals(job.deadline())
                                            || !job.deadline().isAfter(clock.instant()))
                                        throw new PythonClient.Unavailable();
                                    if (stopping
                                            || admission.path("status").asText().equals("CLEARED")
                                            || job.attempts() >= 2)
                                        queue.release(job, "INTERRUPTED");
                                    else send(job);
                                }
                                case "UNKNOWN" -> {
                                    /* Keep the physical slot; absence is not retry permission. */
                                    if (stopping)
                                        queue.observe(
                                                job,
                                                python.cancel(
                                                        job.execution(),
                                                        job.coordinatorBoot(),
                                                        job.pythonBoot(),
                                                        "USER_CANCELLED"));
                                }
                                default -> throw new PythonClient.Unavailable();
                            }
                        }
                        for (var command : queue.commands()) {
                            var accepted =
                                    json(
                                            python.command(
                                                    command.execution(),
                                                    command.id(),
                                                    command.payload()));
                            if (!accepted.path("accepted").asBoolean())
                                throw new PythonClient.Unavailable();
                            queue.acknowledge(command);
                        }
                        if (queue.ready(boot)) {
                            var next = queue.claim(boot, limits);
                            if (next != null) send(next);
                        }
                    } catch (RuntimeException failure) {
                        // A failed observation cannot authorize another execution or reset a paid
                        // attempt.
                        queue.unavailable(boot);
                    }
                    return null;
                });
    }

    private void send(DispatchQueue.Job job) {
        if (!queue.markSending(job)) return;
        try {
            queue.observe(job, python.start(job.execution(), job.payload()));
        } catch (PythonClient.Unavailable unavailable) {
            // The PUT may have committed. Check that exact admission before entering maintenance;
            // otherwise valid callbacks can be rejected during the next polling interval.
            var payload = json(job.payload());
            var admission =
                    json(
                            python.admission(
                                    job.execution(),
                                    job.pythonBoot(),
                                    payload.path("payload_digest").asText(),
                                    job.deadline().toString()));
            if (!job.execution().equals(admission.path("execution_id").asText())
                    || !job.pythonBoot().equals(admission.path("boot_id").asText())
                    || !payload.path("payload_digest").equals(admission.path("payload_digest"))
                    || !java.util.Set.of("ACTIVE", "TERMINAL")
                            .contains(admission.path("status").asText())
                    || admission.path("execution").isNull()) throw unavailable;
            queue.observe(job, admission.path("execution").toString());
        }
    }
}
