package io.auralis.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.auralis")
@EnableConfigurationProperties(OnlineProperties.class)
@EntityScan("io.auralis")
@EnableJpaRepositories("io.auralis")
@EnableScheduling
public class OnlineApplication {
    public static void main(String[] args) {
        try {
            SpringApplication.run(OnlineApplication.class, args);
            System.out.println("AURALIS_ONLINE_STARTED");
        } catch (Exception failure) {
            // Framework exception chains can contain database URLs and rejected credentials.
            System.err.println("AURALIS_STARTUP_FAILED");
            System.exit(1);
        }
    }
}
