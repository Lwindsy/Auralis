package io.auralis.session.application;

import io.auralis.session.api.SessionStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubjectRepository {
    record Subject(
            String id,
            String digest,
            long epoch,
            long revision,
            Instant expiresAt,
            String currentSession,
            String lifecycle) {}

    record Session(
            String id,
            String subject,
            long epoch,
            long revision,
            String status,
            String input,
            Instant expiresAt) {
        public SessionStore.Scope scope(long subjectEpoch) {
            return new SessionStore.Scope(subject, id, subjectEpoch, epoch, revision);
        }
    }

    record Job(String id, String subjectId, String sessionId, long epoch) {}

    Optional<Subject> lockSubject(String id);

    void lockSessions(String subject, List<String> ids);

    boolean barrier(Subject subject, String lifecycle);

    Optional<Subject> find(String digest, String generation, Instant now, boolean lock);

    void generation(String subject, String generation);

    Optional<Session> session(String subject, String session);

    Optional<String> command(String subject, String command, String digest);

    void command(String subject, String command, String digest, String result, Instant at);

    long nextInputRevision(String subject);

    void editable(String session, String input);

    void renew(String subject, String session, Instant at, Instant expires);

    Optional<Session> firstSession(String subject);

    int trimSubjectRows(String subject, int limit);

    void tombstone(Subject subject, Instant now);

    void enqueueSubject(String subject, long epoch, Instant at);

    void enqueueSession(Session session, Instant at);

    List<String> expiredSubjects(Instant now, int limit);

    List<Session> expiredSessions(Instant now, int limit);

    Optional<Job> nextJob(Instant now);

    void defer(String job, Instant at);

    void completed(String job);

    void pruneRevocations(Instant now, int limit);
}
