package io.auralis.agent;

import static io.auralis.contracts.CanonicalJson.JSON;

import io.auralis.bootstrap.OnlineApplication;
import io.auralis.session.api.SubjectSession;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Test-only process invokes the actual lifecycle port; adds no production HTTP route. */
public final class SubjectLifecycleProbe {
    public static void main(String[] args) throws Exception {
        var request = JSON.readTree(System.in);
        var application = new SpringApplication(OnlineApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context =
                application.run(
                        "--auralis.managed-stdin=false", "--auralis.dispatch-enabled=false")) {
            var subjects = context.getBean(SubjectSession.class);
            if (request.path("operation").asText().equals("erase"))
                subjects.erase(request.path("credential").asText(), request.path("csrf").asText());
            subjects.cleanup(100);
            System.out.println("LIFECYCLE_PROBE_OK");
        }
    }
}
