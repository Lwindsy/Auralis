package io.auralis.bootstrap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Private inherited pipe: no public shutdown endpoint, PID files or process-name killing. */
@Component
@ConditionalOnProperty(name = "auralis.managed-stdin", havingValue = "true")
public final class ManagedProcess {
    private final ConfigurableApplicationContext context;
    private final ObjectProvider<DispatchLifecycle> dispatch;

    public ManagedProcess(
            ConfigurableApplicationContext context, ObjectProvider<DispatchLifecycle> dispatch) {
        this.context = context;
        this.dispatch = dispatch;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        var thread =
                new Thread(
                        () -> {
                            try {
                                var input =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        System.in, StandardCharsets.UTF_8));
                                String line;
                                while ((line = input.readLine()) != null) {
                                    if (line.equals("MAINTENANCE")) {
                                        var lifecycle = dispatch.getIfAvailable();
                                        if (lifecycle != null) lifecycle.maintenance();
                                        System.out.println("AURALIS_MAINTENANCE");
                                    } else break;
                                }
                            } catch (Exception failure) {
                                System.err.println("AURALIS_CONTROL_FAILED");
                            } finally {
                                context.close();
                            }
                        },
                        "auralis-process-owner");
        thread.setDaemon(true);
        thread.start();
    }
}
