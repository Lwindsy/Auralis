package io.auralis.session.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.contracts.MessageDefinitions;
import io.auralis.discovery.api.DiscoveryStore;
import io.auralis.discovery.api.SessionExecutions;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.persistence.ShortTransactions;
import io.auralis.publication.api.RecoveryProjection;
import io.auralis.session.api.SessionStore;
import io.auralis.session.api.SubjectSession;
import io.auralis.session.domain.SessionPolicy;
import io.auralis.session.domain.SubjectCredentials;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SubjectSessions implements SubjectSession {
    private static final Duration RETENTION = Duration.ofDays(30);
    private final SubjectRepository subjects;
    private final SessionStore sessions;
    private final DiscoveryStore discovery;
    private final SessionExecutions executions;
    private final RecoveryProjection projection;
    private final ShortTransactions tx;
    private final SubjectCredentials credentials;
    private final Clock clock;
    private final ObjectMapper json;
    private final MessageDefinitions protocol;
    private final SessionPolicy properties;

    public SubjectSessions(
            SubjectRepository subjects,
            SessionStore sessions,
            DiscoveryStore discovery,
            SessionExecutions executions,
            RecoveryProjection projection,
            ShortTransactions tx,
            SubjectCredentials credentials,
            Clock clock,
            ObjectMapper json,
            MessageDefinitions protocol,
            SessionPolicy properties) {
        this.subjects = subjects;
        this.sessions = sessions;
        this.discovery = discovery;
        this.executions = executions;
        this.projection = projection;
        this.tx = tx;
        this.credentials = credentials;
        this.clock = clock;
        this.json = json;
        this.protocol = protocol;
        this.properties = properties;
    }

    private Optional<SubjectRepository.Subject> lookup(String token, boolean lock) {
        if (!credentials.valid(token)) return Optional.empty();
        return subjects.find(
                SubjectCredentials.digest(token), credentials.generation(), clock.instant(), lock);
    }

    private SubjectRepository.Subject require(String token, boolean lock) {
        return lookup(token, lock).orElseThrow(() -> new PersistenceRejected("UNAUTHENTICATED"));
    }

    @Override
    public Optional<Identity> authenticate(String token) {
        return tx.read(
                () ->
                        lookup(token, false)
                                .map(s -> new Identity(s.id(), s.epoch(), s.expiresAt())));
    }

    @Override
    public boolean validCsrf(String token, String csrf) {
        return credentials.valid(token) && SubjectCredentials.same(credentials.csrf(token), csrf);
    }

    private void csrf(String token, String csrf) {
        if (!validCsrf(token, csrf)) throw new PersistenceRejected("FORBIDDEN");
    }

    private ObjectNode parse(String value, String definition) {
        if (value == null) throw new PersistenceRejected("INVALID_PAYLOAD");
        try {
            JsonNode node =
                    json.reader()
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(value);
            if (!node.isObject() || !protocol.validDefinition(definition, node))
                throw new PersistenceRejected("INVALID_PAYLOAD");
            return (ObjectNode) node;
        } catch (java.io.IOException e) {
            throw new PersistenceRejected("INVALID_PAYLOAD");
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = json.createObjectNode();
            var keys = new java.util.TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) result.set(key, canonical(node.get(key)));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }

    private ObjectNode empty(Instant expiry, String message) {
        ObjectNode state = json.createObjectNode();
        state.putNull("session_id");
        state.putNull("round_id");
        state.putNull("run_id");
        state.put("revision", 0);
        state.put("status", "IDLE");
        state.putNull("analysis_mode");
        state.put("recommendation_target_count", properties.recommendationTargetCount());
        state.putArray("slots");
        state.put("supply_state", "CLOSED");
        if (message == null) state.putNull("message_key");
        else state.put("message_key", message);
        state.putArray("input_issues");
        state.putNull("editable_input");
        state.put("expires_at", expiry.toString());
        state.put("restored", false);
        state.putNull("admission");
        return state;
    }

    private SubjectRepository.Session owned(SubjectRepository.Subject subject, String id) {
        if (id == null || !id.equals(subject.currentSession()))
            throw new PersistenceRejected("RESOURCE_UNAVAILABLE");
        var session =
                subjects.session(subject.id(), id)
                        .orElseThrow(() -> new PersistenceRejected("RESOURCE_UNAVAILABLE"));
        if (!session.status().equals("ACTIVE") || !session.expiresAt().isAfter(clock.instant()))
            throw new PersistenceRejected("RESOURCE_UNAVAILABLE");
        return session;
    }

    private ObjectNode render(SubjectRepository.Subject subject, String sessionId) {
        if (sessionId == null) return empty(subject.expiresAt(), null);
        var session = owned(subject, sessionId);
        var scope = session.scope(subject.epoch());
        var state =
                parse(
                        projection
                                .read(scope)
                                .orElseThrow(
                                        () -> new PersistenceRejected("DEPENDENCY_UNAVAILABLE")),
                        "ProductState");
        if (!session.id().equals(state.path("session_id").asText())
                || state.path("revision").asLong() != scope.revision())
            throw new PersistenceRejected("DEPENDENCY_UNAVAILABLE");
        if (session.input() == null) throw new PersistenceRejected("DEPENDENCY_UNAVAILABLE");
        state.set("editable_input", parse(session.input(), "DiscoveryInput"));
        state.put("expires_at", session.expiresAt().toString());
        state.put("restored", true);
        if (state.path("message_key").isNull()) state.put("message_key", "session.restored");
        return state;
    }

    @Override
    public Bootstrap bootstrap(String token) {
        return tx.write(
                () -> {
                    var existing = lookup(token, true);
                    if (existing.isPresent()) {
                        var s = existing.get();
                        return new Bootstrap(
                                token,
                                credentials.csrf(token),
                                s.expiresAt(),
                                render(s, s.currentSession()).toString());
                    }
                    String fresh = credentials.issue(), id = UUID.randomUUID().toString();
                    Instant now = clock.instant(), expiry = now.plus(RETENTION);
                    sessions.createSubject(id, SubjectCredentials.digest(fresh), now, expiry);
                    subjects.generation(id, credentials.generation());
                    return new Bootstrap(
                            fresh,
                            credentials.csrf(fresh),
                            expiry,
                            empty(expiry, token == null ? null : "session.expired").toString());
                });
    }

    @Override
    public String restore(String token, String sessionId) {
        return tx.write(
                () -> {
                    var subject = require(token, true);
                    return render(subject, sessionId == null ? subject.currentSession() : sessionId)
                            .toString();
                });
    }

    @Override
    public Replacement replace(
            String token,
            String csrf,
            String key,
            String expected,
            long revision,
            String input,
            String resolved) {
        csrf(token, csrf);
        if (key == null
                || key.isBlank()
                || key.length() > 160
                || revision < 0
                || revision > Integer.MAX_VALUE) throw new PersistenceRejected("INVALID_PAYLOAD");
        var incoming = parse(input, "DiscoveryInput");
        var canonical = parse(resolved, "RawInput");
        if (incoming.path("liked_genres").isEmpty()
                && incoming.path("disliked_genre_ids").isEmpty()
                && incoming.path("liked_albums").isEmpty()
                && incoming.path("request_text").asText().isBlank())
            throw new PersistenceRejected("INVALID_PAYLOAD");
        String digest =
                SubjectCredentials.digest(
                        canonical(
                                        json.createObjectNode()
                                                .put("expected_session", expected)
                                                .put("revision", revision)
                                                .set("input", incoming))
                                .toString());
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    var subject = require(token, true);
                    var receipt = subjects.command(subject.id(), key, digest);
                    if (receipt.isPresent()) {
                        try {
                            var r = json.readTree(receipt.get());
                            return new Replacement(
                                    new SessionStore.Scope(
                                            subject.id(),
                                            r.path("session_id").asText(),
                                            subject.epoch(),
                                            r.path("epoch").asLong(),
                                            r.path("revision").asLong()),
                                    r.path("previous").isNull()
                                            ? null
                                            : r.path("previous").asText(),
                                    true);
                        } catch (java.io.IOException e) {
                            throw new PersistenceRejected("DEPENDENCY_UNAVAILABLE");
                        }
                    }
                    if (!java.util.Objects.equals(subject.currentSession(), expected))
                        throw new PersistenceRejected("REVISION_CONFLICT");
                    if (expected == null && revision != 0)
                        throw new PersistenceRejected("REVISION_CONFLICT");
                    String id = UUID.randomUUID().toString();
                    subjects.lockSessions(
                            subject.id(), expected == null ? List.of(id) : List.of(id, expected));
                    if (expected != null) {
                        var old = owned(subject, expected);
                        if (old.revision() != revision)
                            throw new PersistenceRejected("REVISION_CONFLICT");
                        sessions.close(old.scope(subject.epoch()), "SUPERSEDED");
                        executions.invalidate(List.of(expected), "SUPERSEDED");
                    }
                    Instant now = clock.instant(), expiry = now.plus(RETENTION);
                    long inputRevision = subjects.nextInputRevision(subject.id());
                    sessions.createSession(
                            id, subject.id(), inputRevision, canonical.toString(), now, expiry);
                    subjects.editable(id, incoming.toString());
                    subjects.renew(subject.id(), null, now, expiry);
                    var created = subjects.session(subject.id(), id).orElseThrow();
                    var scope = created.scope(subject.epoch());
                    var state = empty(expiry, null);
                    state.put("session_id", id);
                    state.put("revision", scope.revision());
                    state.set("editable_input", incoming);
                    projection.save(scope, state.toString());
                    String result =
                            json.createObjectNode()
                                    .put("session_id", id)
                                    .put("revision", scope.revision())
                                    .put("epoch", scope.sessionEpoch())
                                    .put("previous", expected)
                                    .toString();
                    subjects.command(subject.id(), key, digest, result, now);
                    return new Replacement(scope, expected, false);
                });
    }

    private SubjectRepository.Subject authorize(
            String token, String csrf, SessionStore.Scope scope) {
        csrf(token, csrf);
        var s = require(token, true);
        if (!s.id().equals(scope.subjectId()) || s.epoch() != scope.subjectEpoch())
            throw new PersistenceRejected("RESOURCE_UNAVAILABLE");
        var owned = owned(s, scope.sessionId());
        if (!owned.scope(s.epoch()).equals(scope))
            throw new PersistenceRejected("REVISION_CONFLICT");
        sessions.lockActive(scope);
        return s;
    }

    @Override
    public Instant renew(String token, String csrf, SessionStore.Scope scope) {
        return tx.write(
                () -> {
                    var subject = authorize(token, csrf, scope);
                    var state = render(subject, scope.sessionId());
                    Instant now = clock.instant(), expiry = now.plus(RETENTION);
                    subjects.renew(subject.id(), scope.sessionId(), now, expiry);
                    var updated =
                            subjects.session(subject.id(), scope.sessionId())
                                    .orElseThrow()
                                    .scope(subject.epoch());
                    state.put("revision", updated.revision());
                    state.put("expires_at", expiry.toString());
                    state.put("restored", false);
                    projection.save(updated, state.toString());
                    return expiry;
                });
    }

    @Override
    public void heard(String token, String csrf, SessionStore.Scope scope, UUID verifiedAlbum) {
        tx.write(
                () -> {
                    authorize(token, csrf, scope);
                    sessions.recordHeard(scope, verifiedAlbum, clock.instant());
                    renew(token, csrf, scope);
                    return null;
                });
    }

    @Override
    public void erase(String token, String csrf) {
        csrf(token, csrf);
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    var existing = lookup(token, true);
                    if (existing.isEmpty())
                        return null; // Valid old credential: repeated erase has no effect.
                    barrier(existing.get(), "REVOKED");
                    return null;
                });
    }

    private void barrier(SubjectRepository.Subject subject, String state) {
        subjects.tombstone(subject, clock.instant());
        if (subjects.barrier(subject, state)) {
            executions.invalidateSubject(
                    subject.id(), state.equals("EXPIRED") ? "EXPIRED" : "CANCELLED");
            subjects.enqueueSubject(subject.id(), subject.epoch() + 1, clock.instant());
        }
    }

    @Override
    public int cleanup(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("INVALID_BATCH_LIMIT");
        Instant now = clock.instant();
        for (String id : subjects.expiredSubjects(now, credentials.generation(), limit))
            tx.write(
                    () -> {
                        discovery.lockRuntime();
                        var s = subjects.lockSubject(id);
                        if (s.isPresent()
                                && s.get().lifecycle().equals("ACTIVE")
                                && (!s.get().expiresAt().isAfter(now)
                                        || !credentials.generation().equals(s.get().generation())))
                            barrier(s.get(), "EXPIRED");
                        return null;
                    });
        for (var candidate : subjects.expiredSessions(now, limit))
            tx.write(
                    () -> {
                        discovery.lockRuntime();
                        var subject = subjects.lockSubject(candidate.subject());
                        if (subject.isEmpty()) return null;
                        var s = subjects.session(candidate.subject(), candidate.id());
                        if (s.isPresent()
                                && !s.get().expiresAt().isAfter(now)
                                && java.util.Set.of("ACTIVE", "SUPERSEDED")
                                        .contains(s.get().status())) {
                            sessions.close(s.get().scope(subject.get().epoch()), "EXPIRED");
                            executions.invalidate(List.of(s.get().id()), "EXPIRED");
                            subjects.enqueueSession(
                                    subjects.session(candidate.subject(), candidate.id())
                                            .orElseThrow(),
                                    now);
                        }
                        return null;
                    });
        int completed = 0;
        for (int n = 0; n < limit; n++) {
            int result =
                    tx.write(
                            () -> {
                                discovery.lockRuntime();
                                var next = subjects.nextJob(now);
                                if (next.isEmpty()) return -1;
                                var job = next.get();
                                var subject = subjects.lockSubject(job.subjectId());
                                if (subject.isEmpty()) {
                                    subjects.completed(job.id());
                                    return 1;
                                }
                                if (job.sessionId() == null) {
                                    if (subject.get().lifecycle().equals("ACTIVE")
                                            || subject.get().epoch() != job.epoch()) {
                                        subjects.defer(job.id(), now.plusSeconds(30));
                                        return 0;
                                    }
                                    var first = subjects.firstSession(job.subjectId());
                                    if (first.isPresent()) {
                                        var session = first.get();
                                        if (executions.unreleased(List.of(session.id()))) {
                                            subjects.defer(job.id(), now.plusSeconds(30));
                                            return 0;
                                        }
                                        if (!java.util.Set.of("DELETED", "EXPIRED")
                                                .contains(session.status()))
                                            sessions.close(
                                                    session.scope(subject.get().epoch()),
                                                    "DELETED");
                                        var latest =
                                                subjects.session(subject.get().id(), session.id())
                                                        .orElseThrow();
                                        sessions.purgeSession(latest.scope(subject.get().epoch()));
                                        return 0;
                                    }
                                    if (subjects.trimSubjectRows(job.subjectId(), 100) > 0)
                                        return 0;
                                } else if (executions.unreleased(List.of(job.sessionId()))) {
                                    subjects.defer(job.id(), now.plusSeconds(30));
                                    return 0;
                                }
                                boolean purged;
                                if (job.sessionId() == null)
                                    purged = sessions.purgeSubject(subject.get().id(), job.epoch());
                                else {
                                    var session =
                                            subjects.session(subject.get().id(), job.sessionId());
                                    purged =
                                            session.isEmpty()
                                                    || (session.get().epoch() == job.epoch()
                                                            && sessions.purgeSession(
                                                                    session.get()
                                                                            .scope(
                                                                                    subject.get()
                                                                                            .epoch())));
                                }
                                if (purged) {
                                    subjects.completed(job.id());
                                    return 1;
                                }
                                subjects.defer(job.id(), now.plusSeconds(30));
                                return 0;
                            });
            if (result < 0) break;
            completed += result;
        }
        tx.write(
                () -> {
                    subjects.pruneRevocations(now, limit);
                    return null;
                });
        return completed;
    }
}
