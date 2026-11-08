package io.auralis.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.discovery.api.DiscoveryStore;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.persistence.ShortTransactions;
import io.auralis.session.api.SessionStore;
import io.auralis.session.api.SubjectSession;
import io.auralis.session.domain.SubjectCredentials;
import jakarta.servlet.http.HttpServletRequest;
import java.net.*;
import java.net.http.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "AURALIS_TEST_POSTGRES_URL", matches = ".+")
class SubjectSessionIT {
    @Test
    void subjectCleanupAdvancesInBoundedSessionBatches() throws Exception {
        service.cleanup(100);
        try (var browser = new Browser()) {
            browser.bootstrap();
            var a = submit(browser, null, 0, "a", "first");
            var b = submit(browser, a.scope().sessionId(), a.scope().revision(), "b", "second");
            var c = submit(browser, b.scope().sessionId(), b.scope().revision(), "c", "third");
            service.erase(browser.token(), browser.csrf);
            assertEquals(
                    3,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.session WHERE subject_id=?",
                            Integer.class,
                            c.scope().subjectId()));
            service.cleanup(1);
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.session WHERE subject_id=?",
                            Integer.class,
                            c.scope().subjectId()));
            for (int n = 0; n < 6; n++) service.cleanup(1);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.subject WHERE id=?",
                            Integer.class,
                            c.scope().subjectId()));
            assertEquals(0, service.cleanup(1));
        }
    }

    @Test
    void publishedCardsAndSupplyStateSurviveCookieRecoveryWithoutNewExecution() throws Exception {
        try (var browser = new Browser()) {
            browser.bootstrap();
            var created = submit(browser, null, 0, "first", "restore published result");
            var state = (ObjectNode) JSON.readTree(service.restore(browser.token(), null));
            var identity =
                    JSON.createObjectNode()
                            .put("title", "Synthetic album")
                            .put("artist_credit", "Synthetic artist");
            identity.putNull("first_release_year");
            identity.putNull("release_type");
            identity.putArray("primary_genres");
            var review =
                    JSON.createObjectNode()
                            .put("text", "Stored short review")
                            .put("invocation_id", "synthetic-invocation")
                            .put("prompt_version", "synthetic");
            review.putArray("citations")
                    .add(
                            JSON.createObjectNode()
                                    .put("marker", "R1")
                                    .put("read_id", "synthetic-read"));
            var card =
                    JSON.createObjectNode()
                            .put("candidate_id", "synthetic-candidate")
                            .put("long_review_state", "GENERATING");
            card.putNull("cover_url");
            card.set("identity", identity);
            card.set("short_review", review);
            var slot = JSON.createObjectNode().put("slot_id", "first").put("status", "READY");
            slot.set("card", card);
            slot.putNull("message_key");
            state.putArray("slots").add(slot);
            state.put("status", "PARTIAL");
            state.put("analysis_mode", "PERSONALIZED");
            state.put("supply_state", "SUPPLYING");
            tx.write(
                    () -> {
                        var updated = sessions.advanceRevision(created.scope());
                        state.put("revision", updated.revision());
                        context.getBean(io.auralis.publication.api.RecoveryProjection.class)
                                .save(updated, state.toString());
                        return null;
                    });
            long executions =
                    jdbc.queryForObject("SELECT count(*) FROM discovery.execution", Long.class);
            var recovered = browser.bootstrap().path("state");
            assertEquals(state.path("slots"), recovered.path("slots"));
            assertEquals("SUPPLYING", recovered.path("supply_state").asText());
            assertEquals(
                    "GENERATING",
                    recovered.path("slots").get(0).path("card").path("long_review_state").asText());
            assertEquals(
                    executions,
                    jdbc.queryForObject("SELECT count(*) FROM discovery.execution", Long.class));
            var updated = sessions.current(created.scope().subjectId()).orElseThrow().scope();
            assertThrows(
                    PersistenceRejected.class,
                    () ->
                            tx.write(
                                    () -> {
                                        context.getBean(
                                                        io.auralis.publication.api
                                                                .RecoveryProjection.class)
                                                .save(updated, "{}");
                                        return null;
                                    }));
        }
    }

    static final String SECRET = "test-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789";
    static final ObjectMapper JSON = new ObjectMapper();
    static final TestClock CLOCK = new TestClock();
    String url, base;
    ServletWebServerApplicationContext context;
    SubjectSession service;
    JdbcTemplate jdbc;
    ShortTransactions tx;
    SessionStore sessions;
    DiscoveryStore discovery;

    @BeforeAll
    void setup() throws Exception {
        url = System.getenv("AURALIS_TEST_POSTGRES_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        try (var c = admin();
                var s = c.createStatement()) {
            s.execute("CREATE ROLE be006_online LOGIN PASSWORD 'be006-synthetic-password'");
            s.execute("GRANT USAGE ON SCHEMA discovery TO be006_online");
            s.execute(
                    "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO be006_online");
            s.execute("GRANT SELECT ON public.flyway_schema_history TO be006_online");
            s.execute("GRANT SELECT,INSERT,DELETE ON public.bootstrap_probe TO be006_online");
        }
        start(SECRET);
    }

    java.sql.Connection admin() throws Exception {
        return DriverManager.getConnection(
                url, "postgres", System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD"));
    }

    void start(String secret) {
        CLOCK.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var app = new SpringApplication(OnlineApplication.class, TestEndpoints.class);
        app.setAdditionalProfiles("subject-session-it");
        app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("testClock", CLOCK));
        var properties = new HashMap<String, Object>();
        properties.put("auralis.jdbc-url", url);
        properties.put("auralis.database-user", "be006_online");
        properties.put("auralis.database-password", "be006-synthetic-password");
        properties.put("auralis.product-origin", "http://127.0.0.1:8080");
        properties.put("auralis.python-url", "http://127.0.0.1:1");
        properties.put("auralis.service-token", "be006-service-synthetic-token-123456789");
        properties.put("auralis.contract-revision", "0.29");
        properties.put("auralis.subject-secret", secret);
        properties.put("auralis.allow-insecure-cookie", true);
        properties.put("auralis.session-cleanup-enabled", false);
        app.setDefaultProperties(properties);
        context = (ServletWebServerApplicationContext) app.run("--server.port=0");
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
        context.getBean(OnlineProperties.class).setProductOrigin(base);
        service = context.getBean(SubjectSession.class);
        jdbc = context.getBean(JdbcTemplate.class);
        tx = context.getBean(ShortTransactions.class);
        sessions = context.getBean(SessionStore.class);
        discovery = context.getBean(DiscoveryStore.class);
        assertSame(CLOCK, context.getBean(Clock.class));
    }

    @BeforeEach
    void resetClock() {
        CLOCK.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    @AfterAll
    void stop() {
        if (context != null) context.close();
    }

    final class Browser implements AutoCloseable {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;

        HttpResponse<String> call(
                String method, String path, String body, String token, String origin)
                throws Exception {
            var builder =
                    HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
            if (origin != null) builder.header("Origin", origin);
            if (token != null) builder.header("X-CSRF-Token", token);
            if (method.equals("POST"))
                builder.header("Content-Type", "application/json")
                        .header("Idempotency-Key", "http-test")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            else builder.GET();
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        JsonNode bootstrap() throws Exception {
            var response = call("POST", "/v1/bootstrap", "{}", null, base);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode body = JSON.readTree(response.body());
            assertTrue(context.getBean(WireProtocol.class).validDefinition("BootstrapState", body));
            csrf = body.path("csrf_token").asText();
            return body;
        }

        String token() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("auralis_session"))
                    .findFirst()
                    .orElseThrow()
                    .getValue();
        }

        @Override
        public void close() {
            client.close();
        }
    }

    ObjectNode input(String text) {
        var n = JSON.createObjectNode();
        n.putArray("liked_genres");
        n.putArray("disliked_genre_ids");
        n.putArray("liked_albums");
        n.put("mode", "explore");
        n.put("request_text", text);
        n.put("language", "zh");
        return n;
    }

    SubjectSession.Replacement submit(
            Browser browser, String expected, long revision, String command, String text) {
        var input = input(text);
        var resolved =
                input.deepCopy().put("input_digest", SubjectCredentials.digest(input.toString()));
        return service.replace(
                browser.token(),
                browser.csrf,
                command,
                expected,
                revision,
                input.toString(),
                resolved.toString());
    }

    JsonNode state(Browser browser, String path) throws Exception {
        var response = browser.call("GET", path, null, null, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        var body = JSON.readTree(response.body());
        assertTrue(context.getBean(WireProtocol.class).validDefinition("ProductState", body));
        return body;
    }

    @Test
    void bootstrapCookieDigestCsrfAndReadOnlyRecovery() throws Exception {
        try (var browser = new Browser()) {
            var first = browser.call("POST", "/v1/bootstrap", "{}", null, base);
            assertEquals(200, first.statusCode());
            String cookie = first.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(cookie.contains("HttpOnly"));
            assertTrue(cookie.contains("SameSite=Lax"));
            assertFalse(cookie.contains("Secure"));
            var body = JSON.readTree(first.body());
            browser.csrf = body.path("csrf_token").asText();
            String token = browser.token();
            assertFalse(first.body().contains(token));
            var identity = service.authenticate(token).orElseThrow();
            assertEquals(
                    SubjectCredentials.digest(token),
                    jdbc.queryForObject(
                            "SELECT credential_digest FROM discovery.subject WHERE id=?",
                            String.class,
                            identity.subjectId()));
            var created =
                    submit(browser, null, 0, "new", "private request retained on server only");
            Instant expiry = service.authenticate(token).orElseThrow().expiresAt();
            CLOCK.now = CLOCK.now.plus(Duration.ofDays(5));
            var restored = state(browser, "/v1/current-state");
            assertEquals(created.scope().sessionId(), restored.path("session_id").asText());
            assertTrue(restored.path("restored").asBoolean());
            assertEquals(
                    "private request retained on server only",
                    restored.path("editable_input").path("request_text").asText());
            browser.bootstrap();
            assertEquals(token, browser.token());
            assertEquals(expiry, service.authenticate(token).orElseThrow().expiresAt());
            assertEquals(
                    restored.path("revision"),
                    state(browser, "/v1/current-state").path("revision"));
        }
    }

    @Test
    void csrfOriginAndCrossSubjectIdsAreRejectedWithoutExistenceLeak() throws Exception {
        try (var a = new Browser();
                var b = new Browser()) {
            a.bootstrap();
            b.bootstrap();
            var owned = submit(a, null, 0, "new", "owner a");
            var foreign =
                    b.call(
                            "GET",
                            "/v1/discovery-sessions/" + owned.scope().sessionId(),
                            null,
                            null,
                            null);
            var absent =
                    b.call("GET", "/v1/discovery-sessions/" + UUID.randomUUID(), null, null, null);
            assertEquals(400, foreign.statusCode());
            assertEquals(foreign.statusCode(), absent.statusCode());
            assertEquals(
                    JSON.readTree(foreign.body()).path("code"),
                    JSON.readTree(absent.body()).path("code"));
            assertFalse(foreign.body().contains(owned.scope().sessionId()));
            assertEquals(
                    403,
                    a.call("POST", "/v1/bootstrap", "{}", null, "https://attacker.invalid")
                            .statusCode());
            assertEquals(
                    400,
                    a.call(
                                    "POST",
                                    "/v1/_test/renew/" + owned.scope().sessionId(),
                                    "{}",
                                    b.csrf,
                                    base)
                            .statusCode());
            assertEquals(
                    400,
                    b.call(
                                    "POST",
                                    "/v1/_test/renew/" + owned.scope().sessionId(),
                                    "{}",
                                    b.csrf,
                                    base)
                            .statusCode());
            assertEquals(
                    400,
                    a.call("POST", "/v1/_test/renew/" + owned.scope().sessionId(), "{}", null, base)
                            .statusCode());
            assertEquals(
                    403,
                    a.call("POST", "/v1/bootstrap", "{\"subject_id\":\"forged\"}", null, base)
                            .statusCode());
            assertEquals(403, a.call("POST", "/v1/bootstrap", "{}{}", null, base).statusCode());
            assertEquals(
                    owned.scope().sessionId(),
                    state(a, "/v1/current-state?subject_id=forged").path("session_id").asText());
        }
    }

    @Test
    void activeOperationRenewsCookieAndReplacementPreservesHeardButHidesOldSession()
            throws Exception {
        try (var browser = new Browser()) {
            browser.bootstrap();
            var first = submit(browser, null, 0, "first", "initial request");
            UUID album = UUID.randomUUID();
            CLOCK.now = CLOCK.now.plus(Duration.ofDays(3));
            var renewed =
                    browser.call(
                            "POST",
                            "/v1/_test/renew/" + first.scope().sessionId(),
                            "{}",
                            browser.csrf,
                            base);
            assertEquals(200, renewed.statusCode(), renewed.body());
            assertTrue(renewed.headers().firstValue("Set-Cookie").isPresent());
            var owner = service.authenticate(browser.token()).orElseThrow();
            assertEquals(CLOCK.instant().plus(Duration.ofDays(30)), owner.expiresAt());
            var scope = sessions.current(owner.subjectId()).orElseThrow().scope();
            service.heard(browser.token(), browser.csrf, scope, album);
            scope = sessions.current(owner.subjectId()).orElseThrow().scope();
            var replacement =
                    submit(browser, scope.sessionId(), scope.revision(), "replace", "new request");
            assertNotEquals(first.scope().sessionId(), replacement.scope().sessionId());
            assertEquals(
                    "SUPERSEDED",
                    jdbc.queryForObject(
                            "SELECT status FROM discovery.session WHERE id=?",
                            String.class,
                            first.scope().sessionId()));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.heard_album WHERE subject_id=? AND release_group_id=?",
                            Integer.class,
                            owner.subjectId(),
                            album));
            assertEquals(
                    400,
                    browser.call(
                                    "GET",
                                    "/v1/discovery-sessions/" + first.scope().sessionId(),
                                    null,
                                    null,
                                    null)
                            .statusCode());
            var replay =
                    submit(browser, scope.sessionId(), scope.revision(), "replace", "new request");
            assertTrue(replay.replayed());
            assertEquals(replacement.scope().sessionId(), replay.scope().sessionId());
            var stale = scope;
            assertThrows(
                    PersistenceRejected.class,
                    () ->
                            submit(
                                    browser,
                                    stale.sessionId(),
                                    stale.revision(),
                                    "different-command",
                                    "conflicting new request"));
        }
    }

    @Test
    void expiredCredentialStartsFreshAndCleanupIsIdempotent() throws Exception {
        try (var browser = new Browser()) {
            browser.bootstrap();
            var old = submit(browser, null, 0, "first", "will expire");
            String credential = browser.token();
            CLOCK.now = CLOCK.now.plus(Duration.ofDays(30));
            assertTrue(service.authenticate(credential).isEmpty());
            assertEquals(
                    400, browser.call("GET", "/v1/current-state", null, null, null).statusCode());
            var fresh = browser.bootstrap();
            assertTrue(fresh.path("state").path("session_id").isNull());
            assertEquals("session.expired", fresh.path("state").path("message_key").asText());
            assertNotEquals(credential, browser.token());
            service.cleanup(100);
            service.cleanup(100);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.session WHERE id=?",
                            Integer.class,
                            old.scope().sessionId()));
        }
    }

    @Test
    void deletionWaitsForPhysicalReleaseAndLateWritesCannotRestoreInput() throws Exception {
        try (var browser = new Browser()) {
            browser.bootstrap();
            var created = submit(browser, null, 0, "first", "delete me");
            var scope = created.scope();
            String round = UUID.randomUUID().toString(),
                    run = UUID.randomUUID().toString(),
                    execution = UUID.randomUUID().toString();
            tx.write(
                    () -> {
                        discovery.lockRuntime();
                        discovery.createRound(
                                scope, round, 1, "{}", "{}", CLOCK.instant().plusSeconds(120));
                        discovery.createIntent(
                                scope,
                                round,
                                run,
                                "test",
                                1,
                                SubjectCredentials.digest("test"),
                                CLOCK.instant(),
                                CLOCK.instant().plusSeconds(30));
                        discovery.reserveExecution(
                                scope, round, run, execution, 3, "coordinator", "python");
                        return null;
                    });
            service.erase(browser.token(), browser.csrf);
            service.erase(browser.token(), browser.csrf);
            assertTrue(service.authenticate(browser.token()).isEmpty());
            assertEquals(
                    400, browser.call("GET", "/v1/current-state", null, null, null).statusCode());
            assertTrue(
                    jdbc.queryForObject(
                            "SELECT cancellation_requested FROM discovery.execution WHERE id=?",
                            Boolean.class,
                            execution));
            service.cleanup(100);
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.session WHERE id=?",
                            Integer.class,
                            scope.sessionId()));
            assertThrows(
                    PersistenceRejected.class,
                    () ->
                            tx.write(
                                    () -> {
                                        sessions.recordHeard(
                                                scope, UUID.randomUUID(), CLOCK.instant());
                                        return null;
                                    }));
            tx.write(
                    () ->
                            discovery.releaseExecution(
                                    scope, execution, "coordinator", "python", CLOCK.instant()));
            CLOCK.now = CLOCK.now.plusSeconds(31);
            service.cleanup(100);
            service.cleanup(100);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.subject WHERE id=?",
                            Integer.class,
                            scope.subjectId()));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.recovery_projection WHERE session_id=?",
                            Integer.class,
                            scope.sessionId()));
        }
    }

    @Test
    void restartRestoresInputAndIndependentKeyRotationRejectsRestoredCredentials()
            throws Exception {
        try (var browser = new Browser()) {
            browser.bootstrap();
            var created = submit(browser, null, 0, "first", "survive restart");
            String original = browser.token();
            context.close();
            start(SECRET);
            assertEquals(
                    created.scope().sessionId(),
                    state(browser, "/v1/current-state").path("session_id").asText());
            context.close();
            start("rotated-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
            assertEquals(
                    400, browser.call("GET", "/v1/current-state", null, null, null).statusCode());
            service.cleanup(100);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.subject WHERE id=?",
                            Integer.class,
                            created.scope().subjectId()));
            var newState = browser.bootstrap();
            assertTrue(newState.path("state").path("session_id").isNull());
            assertNotEquals(original, browser.token());
        } finally {
            context.close();
            start(SECRET);
        }
    }

    @Test
    void replayedRevocationBlocksAnOldDatabaseRow() throws Exception {
        try (var browser = new Browser()) {
            browser.bootstrap();
            String token = browser.token(),
                    subject = service.authenticate(token).orElseThrow().subjectId();
            String backup =
                    jdbc.queryForObject(
                            "SELECT row_to_json(s)::text FROM discovery.subject s WHERE id=?",
                            String.class,
                            subject);
            service.erase(token, browser.csrf);
            service.cleanup(100);
            try (var c = admin();
                    var statement =
                            c.prepareStatement(
                                    "INSERT INTO discovery.subject SELECT * FROM jsonb_populate_record(NULL::discovery.subject,?::jsonb)")) {
                statement.setString(1, backup);
                statement.executeUpdate();
            }
            assertTrue(service.authenticate(token).isEmpty());
            assertEquals(
                    400, browser.call("GET", "/v1/current-state", null, null, null).statusCode());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.subject_revocation WHERE credential_digest=?",
                            Integer.class,
                            SubjectCredentials.digest(token)));
        }
    }

    @Test
    void concurrentModificationAcceptsOneNewSession() throws Exception {
        try (var browser = new Browser();
                var pool = Executors.newFixedThreadPool(2)) {
            browser.bootstrap();
            var first = submit(browser, null, 0, "first", "old");
            var scope = first.scope();
            var gate = new CountDownLatch(1);
            Callable<Boolean> change =
                    () -> {
                        gate.await();
                        try {
                            submit(
                                    browser,
                                    scope.sessionId(),
                                    scope.revision(),
                                    UUID.randomUUID().toString(),
                                    "updated");
                            return true;
                        } catch (PersistenceRejected rejected) {
                            assertEquals("REVISION_CONFLICT", rejected.getMessage());
                            return false;
                        }
                    };
            var a = pool.submit(change);
            var b = pool.submit(change);
            gate.countDown();
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.session WHERE subject_id=? AND status='ACTIVE'",
                            Integer.class,
                            scope.subjectId()));
        }
    }

    static class TestClock extends Clock {
        volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @RestController
    @Profile("subject-session-it")
    static class TestEndpoints {
        private final SubjectSession service;
        private final SessionStore sessions;

        TestEndpoints(SubjectSession service, SessionStore sessions) {
            this.service = service;
            this.sessions = sessions;
        }

        @PostMapping("/v1/_test/renew/{id}")
        Map<String, String> renew(@PathVariable String id, HttpServletRequest request) {
            String token = SubjectCookies.credential(request);
            var identity = service.authenticate(token).orElseThrow();
            var scope =
                    sessions.current(identity.subjectId())
                            .map(SessionStore.Snapshot::scope)
                            .orElse(
                                    new SessionStore.Scope(
                                            identity.subjectId(), id, identity.epoch(), 0, 0));
            scope =
                    new SessionStore.Scope(
                            identity.subjectId(),
                            id,
                            scope.subjectEpoch(),
                            scope.sessionEpoch(),
                            scope.revision());
            return Map.of(
                    "expires_at",
                    service.renew(token, request.getHeader("X-CSRF-Token"), scope).toString());
        }
    }
}
