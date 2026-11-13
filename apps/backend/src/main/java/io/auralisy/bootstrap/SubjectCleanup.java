package io.auralisy.bootstrap;

import io.auralisy.session.api.SubjectSession;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SubjectCleanup {
    private final SubjectSession sessions;
    private final OnlineProperties properties;

    public SubjectCleanup(SubjectSession sessions, OnlineProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void run() {
        if (properties.isSessionCleanupEnabled()) sessions.cleanup(20);
    }
}
