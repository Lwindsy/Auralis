package io.auralisy.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.auralisy")
@EnableConfigurationProperties(OnlineProperties.class)
@EntityScan("io.auralisy")
@EnableJpaRepositories("io.auralisy")
@EnableScheduling
public class OnlineApplication {
    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("--offline-migrate")) {
                OfflineMigration.run();
                return;
            }
            SpringApplication.run(OnlineApplication.class, args);
            System.out.println("AURALIS_ONLINE_STARTED");
        } catch (Exception failure) {
            // Framework exception chains can contain database URLs and rejected credentials.
            System.err.println(startupFailure(failure));
            System.exit(1);
        }
    }

    static String startupFailure(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return "AURALIS_STARTUP_FAILED " + failure.getClass().getSimpleName();
    }
}
