package io.auralis.session.adapters;

import static org.junit.jupiter.api.Assertions.*;

import io.auralis.bootstrap.OnlineApplication;
import io.auralis.catalog.api.AudienceStore;
import io.auralis.discovery.api.DiscoveryStore;
import io.auralis.evidence.api.EvidenceStore;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.persistence.ShortTransactions;
import io.auralis.preference.api.PreferenceStore;
import io.auralis.publication.api.PublicationStore;
import io.auralis.session.api.SessionStore;
import io.auralis.session.api.SessionStore.Scope;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "AURALIS_TEST_POSTGRES_URL", matches = ".+")
class DiscoveryPersistenceIT {
    @Test
    void connectionPoolWaitIsBoundedAndStatusReadHasNoSideEffects() throws Exception {
        var scope = session();
        var before = sessions.current(scope.subjectId()).orElseThrow();
        var view = tx.read(() -> sessions.status(scope.subjectId())).orElseThrow();
        assertEquals(scope, view.scope());
        assertEquals(before.expiresAt(), view.expiresAt());
        var pool = context.getBean(com.zaxxer.hikari.HikariDataSource.class);
        var held = new ArrayList<java.sql.Connection>();
        try {
            for (int n = 0; n < pool.getMaximumPoolSize(); n++) held.add(pool.getConnection());
            assertTimeoutPreemptively(
                    Duration.ofSeconds(4),
                    () ->
                            assertThrows(
                                    java.sql.SQLTransientConnectionException.class,
                                    pool::getConnection));
        } finally {
            for (var c : held) c.close();
        }
        assertTrue(sessions.status(scope.subjectId()).isPresent());
        tx.write(() -> sessions.close(scope, "DELETED"));
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () ->
                                        jdbc.update(
                                                "UPDATE discovery.session SET raw_input='{}',revision=revision+1 WHERE id=?",
                                                scope.sessionId())));
        assertTrue(sessions.status(scope.subjectId()).isEmpty());
    }

    @Test
    void expiredSessionsCanBeBarrieredAndPurgedWithoutBeingReadable() {
        String subject = id(), session = id();
        Instant past = Instant.now().minusSeconds(100);
        tx.write(
                () -> {
                    sessions.createSubject(
                            subject,
                            "sha256:" + subject.replace("-", "").repeat(2),
                            past,
                            Instant.now().plusSeconds(3600));
                    sessions.createSession(session, subject, 1, "{}", past, past.plusSeconds(10));
                    return null;
                });
        var scope = new Scope(subject, session, 0, 0, 0);
        assertTrue(sessions.current(subject).isEmpty());
        assertFalse(tx.write(() -> sessions.purgeSession(scope)));
        assertThrows(
                PersistenceRejected.class,
                () ->
                        tx.write(
                                () -> {
                                    sessions.scheduleDeletion(id(), scope, Instant.now());
                                    return null;
                                }));
        assertTrue(tx.write(() -> sessions.close(scope, "EXPIRED")));
        assertTrue(tx.write(() -> sessions.purgeSession(new Scope(subject, session, 0, 1, 1))));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.session WHERE id=?",
                        Integer.class,
                        session));
    }

    @Test
    void receiptsAreIdempotentAndBarrierRejectsLatePersonalWrites() {
        var scope = session();
        String round = round(scope), run = intent(scope, round), execution = id();
        String read = id(), delivery = id(), seed = id();
        UUID album = UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        tx.write(
                () -> {
                    discovery.reserveExecution(
                            scope, round, run, execution, 2, "coordinator", "python");
                    return null;
                });
        try {
            var r =
                    new EvidenceStore.Receipt(
                            read,
                            round,
                            execution,
                            seed,
                            album,
                            "https://example.test/review",
                            at,
                            "READ_OK",
                            DIGEST,
                            "{}");
            var e =
                    new EvidenceStore.Seed(
                            seed,
                            "identity-v1",
                            "data-v1",
                            read,
                            null,
                            DIGEST,
                            at,
                            at.plusSeconds(100),
                            "READ_AVAILABLE",
                            "{\"excerpt\":\"bounded\"}");
            tx.write(
                    () -> {
                        evidence.claim(scope, round, execution, read, DIGEST, at);
                        evidence.claim(scope, round, execution, read, DIGEST, at);
                        evidence.saveReceipt(scope, r);
                        evidence.saveReceipt(scope, r);
                        evidence.saveSeed(scope, e);
                        evidence.saveSeed(scope, e);
                        return null;
                    });
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.read_claim WHERE read_id=?",
                            Integer.class,
                            read));
            assertTrue(tx.write(() -> evidence.seed(scope, seed, "identity-v1")).isPresent());
            assertThrows(
                    PersistenceRejected.class,
                    () ->
                            tx.write(
                                    () -> {
                                        evidence.saveSeed(
                                                scope,
                                                new EvidenceStore.Seed(
                                                        seed,
                                                        "identity-v1",
                                                        "data-v1",
                                                        read,
                                                        null,
                                                        DIGEST,
                                                        at,
                                                        at.plusSeconds(100),
                                                        "READ_AVAILABLE",
                                                        "{\"excerpt\":\"changed\"}"));
                                        return null;
                                    }));
            tx.write(
                    () -> {
                        publication.saveDelivery(
                                scope, delivery, round, execution, 1, DIGEST, "{}");
                        publication.saveDelivery(
                                scope, delivery, round, execution, 1, DIGEST, "{}");
                        return null;
                    });
            assertThrows(
                    PersistenceRejected.class,
                    () ->
                            tx.write(
                                    () -> {
                                        publication.saveDelivery(
                                                scope,
                                                delivery,
                                                round,
                                                execution,
                                                1,
                                                "sha256:" + "b".repeat(64),
                                                "{}");
                                        return null;
                                    }));
            String atomic = id();
            assertThrows(
                    Exception.class,
                    () ->
                            tx.write(
                                    () -> {
                                        publication.saveDelivery(
                                                scope, atomic, round, execution, 2, DIGEST, "{}");
                                        publication.saveCard(
                                                scope,
                                                round,
                                                "nonexistent",
                                                "{}",
                                                "PERSONALIZED",
                                                "READY");
                                        return null;
                                    }));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.delivery_receipt WHERE id=?",
                            Integer.class,
                            atomic));
            tx.write(() -> sessions.close(scope, "DELETED"));
            assertThrows(
                    PersistenceRejected.class,
                    () ->
                            tx.write(
                                    () -> {
                                        evidence.saveReceipt(scope, r);
                                        return null;
                                    }));
        } finally {
            assertTrue(
                    tx.write(
                            () ->
                                    discovery.releaseExecution(
                                            scope,
                                            execution,
                                            "coordinator",
                                            "python",
                                            Instant.now())));
        }
        long revision =
                jdbc.queryForObject(
                        "SELECT revision FROM discovery.subject WHERE id=?",
                        Long.class,
                        scope.subjectId());
        assertTrue(
                tx.write(
                        () ->
                                sessions.revokeSubject(
                                        scope.subjectId(), revision, scope.subjectEpoch())));
        assertTrue(
                tx.write(() -> sessions.purgeSubject(scope.subjectId(), scope.subjectEpoch() + 1)));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.seed_evidence WHERE session_id=?",
                        Integer.class,
                        scope.sessionId()));
    }

    @Test
    void commandKeysDoNotReplayDifferentRequestsAndRetiredRunsCannotRestart() {
        var scope = session();
        String command = id(), round = round(scope), run = intent(scope, round);
        tx.write(
                () -> {
                    sessions.saveCommand(
                            scope,
                            "submit",
                            command,
                            DIGEST,
                            "{\"run\":\"original\"}",
                            Instant.now());
                    sessions.saveCommand(
                            scope,
                            "submit",
                            command,
                            DIGEST,
                            "{\"run\":\"ignored\"}",
                            Instant.now());
                    return null;
                });
        assertTrue(
                tx.write(() -> sessions.command(scope, "submit", command, DIGEST))
                        .orElseThrow()
                        .contains("original"));
        assertThrows(
                PersistenceRejected.class,
                () ->
                        tx.write(
                                () ->
                                        sessions.command(
                                                scope,
                                                "submit",
                                                command,
                                                "sha256:" + "b".repeat(64))));
        assertTrue(tx.write(() -> discovery.transitionRun(scope, run, 0, "WAITING", "EXPIRED")));
        assertThrows(
                Exception.class,
                () -> tx.write(() -> discovery.transitionRun(scope, run, 1, "EXPIRED", "WAITING")));
        tx.write(
                () -> {
                    sessions.close(scope, "DELETED");
                    String cleanup = id();
                    var barrier =
                            new Scope(
                                    scope.subjectId(),
                                    scope.sessionId(),
                                    scope.subjectEpoch(),
                                    scope.sessionEpoch() + 1,
                                    scope.revision() + 1);
                    sessions.scheduleDeletion(cleanup, barrier, Instant.now());
                    return null;
                });
    }

    static final String PASSWORD = "be005-synthetic-password";
    static final String DIGEST = "sha256:" + "a".repeat(64);
    String url;
    ConfigurableApplicationContext context;
    JdbcTemplate jdbc;
    ShortTransactions tx;
    SessionStore sessions;
    DiscoveryStore discovery;
    PreferenceStore preferences;
    EvidenceStore evidence;
    PublicationStore publication;
    AudienceStore audience;

    @BeforeAll
    void start() throws Exception {
        url = System.getenv("AURALIS_TEST_POSTGRES_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        try (var c = admin(url);
                var s = c.createStatement()) {
            s.execute("CREATE ROLE be005_migrator LOGIN PASSWORD 'be005-synthetic-password'");
            s.execute("CREATE ROLE be005_online LOGIN PASSWORD 'be005-synthetic-password'");
            s.execute("GRANT CREATE ON DATABASE auralis_core_verify TO be005_migrator");
            s.execute("GRANT USAGE,CREATE ON SCHEMA public TO be005_migrator");
            // Main verify may already have applied the same immutable migration through BE-004's
            // test role.
            s.execute("GRANT USAGE ON SCHEMA discovery TO be005_online");
            s.execute(
                    "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO be005_online");
            s.execute("GRANT SELECT ON public.flyway_schema_history TO be005_online");
            s.execute("GRANT SELECT,INSERT,DELETE ON public.bootstrap_probe TO be005_online");
        }
        var app = new SpringApplication(OnlineApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(
                Map.of(
                        "auralis.jdbc-url",
                        url,
                        "auralis.database-user",
                        "be005_online",
                        "auralis.database-password",
                        PASSWORD,
                        "auralis.product-origin",
                        "http://127.0.0.1:8080",
                        "auralis.python-url",
                        "http://127.0.0.1:1",
                        "auralis.service-token",
                        "be005-synthetic-service-token-12345",
                        "auralis.contract-revision",
                        "0.31"));
        context =
                app.run(
                        "--auralis.subject-secret=test-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789",
                        "--auralis.allow-insecure-cookie=true",
                        "--auralis.session-cleanup-enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        tx = context.getBean(ShortTransactions.class);
        sessions = context.getBean(SessionStore.class);
        discovery = context.getBean(DiscoveryStore.class);
        preferences = context.getBean(PreferenceStore.class);
        evidence = context.getBean(EvidenceStore.class);
        publication = context.getBean(PublicationStore.class);
        audience = context.getBean(AudienceStore.class);
    }

    java.sql.Connection admin(String address) throws Exception {
        return DriverManager.getConnection(
                address, "postgres", System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD"));
    }

    @AfterAll
    void stop() {
        if (context != null) context.close();
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    Scope session() {
        String subject = id(), session = id();
        Instant now = Instant.now();
        tx.write(
                () -> {
                    sessions.createSubject(
                            subject,
                            "sha256:" + subject.replace("-", "").repeat(2),
                            now,
                            now.plusSeconds(3600));
                    sessions.createSession(
                            session,
                            subject,
                            1,
                            "{\"request_text\":\"private test text\"}",
                            now,
                            now.plusSeconds(3600));
                    return null;
                });
        return sessions.current(subject).orElseThrow().scope();
    }

    String round(Scope scope) {
        String round = id();
        tx.write(
                () -> {
                    discovery.createRound(
                            scope, round, 1, "{}", "{}", Instant.now().plusSeconds(120));
                    return null;
                });
        return round;
    }

    String intent(Scope scope, String round) {
        String run = id();
        tx.write(
                () -> {
                    discovery.createIntent(
                            scope,
                            round,
                            run,
                            "DISCOVERY",
                            1,
                            DIGEST,
                            Instant.now(),
                            Instant.now().plusSeconds(30));
                    return null;
                });
        return run;
    }

    @Test
    void v14PreservesPendingDeletionWorkAndRetryState() throws Exception {
        String target = url.substring(0, url.lastIndexOf('/') + 1) + "auralis_cleanup_v14";
        try (var c = admin(url);
                var statement = c.createStatement()) {
            statement.execute("CREATE DATABASE auralis_cleanup_v14 OWNER be005_migrator");
        }
        Flyway.configure()
                .dataSource(target, "be005_migrator", PASSWORD)
                .locations("classpath:db/migration")
                .target("13")
                .load()
                .migrate();
        try (var c = DriverManager.getConnection(target, "be005_migrator", PASSWORD);
                var statement = c.createStatement()) {
            statement.execute(
                    "INSERT INTO discovery.subject(id,credential_digest,lifecycle,last_user_action,expires_at) VALUES ('subject','sha256:' || repeat('a',64),'REVOKED','2026-01-01','2026-02-01')");
            statement.execute(
                    "INSERT INTO discovery.deletion_job(id,subject_id,barrier_epoch,state,step,cursor,attempts,next_attempt_at) VALUES ('pending','subject',7,'FAILED','WAIT_RELEASE','unused',3,'2026-01-02T00:00:00Z')");
        }
        var migration =
                Flyway.configure()
                        .dataSource(target, "be005_migrator", PASSWORD)
                        .locations("classpath:db/migration")
                        .load();
        migration.migrate();
        migration.validate();
        try (var c = DriverManager.getConnection(target, "be005_migrator", PASSWORD);
                var statement = c.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT id,barrier_epoch,attempts,next_attempt_at FROM discovery.deletion_job")) {
            assertTrue(rows.next());
            assertEquals("pending", rows.getString(1));
            assertEquals(7, rows.getLong(2));
            assertEquals(3, rows.getInt(3));
            assertEquals(Instant.parse("2026-01-02T00:00:00Z"), rows.getTimestamp(4).toInstant());
            assertFalse(rows.next());
        }
    }

    @Test
    void emptyInstallAndV6UpgradePreserveDataAndChecksums() throws Exception {
        for (String name : List.of("auralis_be005_fresh", "auralis_be005_upgrade")) {
            try (var c = admin(url);
                    var s = c.createStatement()) {
                s.execute("CREATE DATABASE " + name + " OWNER be005_migrator");
            }
            String target = url.substring(0, url.lastIndexOf('/') + 1) + name;
            var config =
                    Flyway.configure()
                            .dataSource(target, "be005_migrator", PASSWORD)
                            .locations("classpath:db/migration");
            if (name.endsWith("upgrade")) {
                config.target("6").load().migrate();
                try (var c = DriverManager.getConnection(target, "be005_migrator", PASSWORD);
                        var s = c.createStatement()) {
                    s.execute("CREATE TABLE public.be005_sentinel(id int primary key)");
                    s.execute("INSERT INTO public.be005_sentinel VALUES(7)");
                }
            }
            var flyway =
                    Flyway.configure()
                            .dataSource(target, "be005_migrator", PASSWORD)
                            .locations("classpath:db/migration")
                            .load();
            flyway.migrate();
            flyway.validate();
            assertEquals("14", flyway.info().current().getVersion().toString());
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (var c = DriverManager.getConnection(target, "be005_migrator", PASSWORD);
                    var s = c.createStatement()) {
                if (name.endsWith("upgrade")) {
                    var rs = s.executeQuery("SELECT id FROM public.be005_sentinel");
                    assertTrue(rs.next());
                    assertEquals(7, rs.getInt(1));
                    rs.close();
                }
                var rs =
                        s.executeQuery(
                                "SELECT checksum FROM flyway_schema_history WHERE version='7'");
                rs.next();
                int checksum = rs.getInt(1);
                rs.close();
                s.execute("UPDATE flyway_schema_history SET checksum=1 WHERE version='7'");
                assertThrows(Exception.class, flyway::validate);
                s.execute(
                        "UPDATE flyway_schema_history SET checksum="
                                + checksum
                                + " WHERE version='7'");
                flyway.validate();
            }
        }
    }

    @Test
    void jpaJdbcRollbackAndStaleEntityCannotRestoreDeletedContent() {
        String subject = id(), session = id();
        Instant now = Instant.now();
        assertThrows(
                PersistenceRejected.class,
                () ->
                        tx.write(
                                () -> {
                                    sessions.createSubject(
                                            subject, DIGEST, now, now.plusSeconds(3600));
                                    sessions.createSession(
                                            session, subject, 1, "{}", now, now.plusSeconds(3600));
                                    jdbc.update(
                                            "INSERT INTO discovery.heard_album VALUES (?,?,?)",
                                            subject,
                                            UUID.randomUUID(),
                                            Timestamp.from(now));
                                    throw new PersistenceRejected("rollback");
                                }));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.subject WHERE id=?",
                        Integer.class,
                        subject));
        var scope = session();
        var factory = context.getBean(EntityManagerFactory.class);
        var stale = factory.createEntityManager();
        var entity = stale.find(SessionEntity.class, scope.sessionId());
        assertEquals("ACTIVE", entity.status);
        tx.write(() -> sessions.close(scope, "DELETED"));
        assertTrue(sessions.current(scope.subjectId()).isEmpty());
        stale.getTransaction().begin();
        entity.rawInput = "{\"request_text\":\"late overwrite\"}";
        assertThrows(
                Exception.class,
                () -> {
                    stale.flush();
                    stale.getTransaction().commit();
                });
        if (stale.getTransaction().isActive()) stale.getTransaction().rollback();
        stale.close();
        assertEquals(
                "DELETED",
                jdbc.queryForObject(
                        "SELECT status FROM discovery.session WHERE id=?",
                        String.class,
                        scope.sessionId()));
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () ->
                                        jdbc.update(
                                                "UPDATE discovery.session SET status='ACTIVE',revision=revision+1 WHERE id=?",
                                                scope.sessionId())));
        assertThrows(
                PersistenceRejected.class,
                () ->
                        tx.write(
                                () -> {
                                    preferences.save(scope, id(), DIGEST, "v1", "v1", DIGEST, "{}");
                                    return null;
                                }));
    }

    @Test
    void ownJdbcTransitionClearsManagedEntityAndReadDoesNotRenew() {
        var scope = session();
        var before = sessions.current(scope.subjectId()).orElseThrow().expiresAt();
        tx.write(
                () -> {
                    var em =
                            EntityManagerFactoryUtils.getTransactionalEntityManager(
                                    context.getBean(EntityManagerFactory.class));
                    var entity = em.find(SessionEntity.class, scope.sessionId());
                    assertTrue(em.contains(entity));
                    assertTrue(sessions.close(scope, "SUPERSEDED"));
                    assertFalse(em.contains(entity));
                    entity.rawInput = "{\"ignored\":true}";
                    em.flush();
                    return null;
                });
        assertTrue(sessions.current(scope.subjectId()).isEmpty());
        assertEquals(
                before,
                jdbc.queryForObject(
                                "SELECT expires_at FROM discovery.session WHERE id=?",
                                Timestamp.class,
                                scope.sessionId())
                        .toInstant());
        assertEquals(
                "SUPERSEDED",
                jdbc.queryForObject(
                        "SELECT status FROM discovery.session WHERE id=?",
                        String.class,
                        scope.sessionId()));
    }

    @Test
    void uniquenessOwnershipAndFiniteDocumentsAreDatabaseConstraints() {
        var a = session();
        var b = session();
        String round = round(a);
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () -> {
                                    discovery.createRound(a, id(), 1, "{}", "{}", Instant.now());
                                    return null;
                                }));
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () -> {
                                    discovery.createRound(a, id(), 6, "{}", "{}", Instant.now());
                                    return null;
                                }));
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () -> {
                                    publication.addCandidate(
                                            b,
                                            new PublicationStore.Candidate(
                                                    round,
                                                    id(),
                                                    UUID.randomUUID(),
                                                    0,
                                                    "{}",
                                                    "{}",
                                                    "READY"));
                                    return null;
                                }));
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () -> {
                                    preferences.save(a, id(), DIGEST, "v1", "v1", DIGEST, "[]");
                                    return null;
                                }));
        String resolution = id();
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () ->
                                        jdbc.update(
                                                "INSERT INTO discovery.effective_preference VALUES (?,? ,?,'v1','v1',?,jsonb_build_object('text',repeat('x',16777216)))",
                                                a.sessionId(),
                                                id(),
                                                DIGEST,
                                                DIGEST)));
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () -> {
                                    preferences.save(
                                            a, "x".repeat(161), DIGEST, "v1", "v1", DIGEST, "{}");
                                    return null;
                                }));
        tx.write(
                () -> {
                    preferences.save(a, resolution, DIGEST, "v1", "v1", DIGEST, "{\"ok\":true}");
                    return null;
                });
        assertThrows(
                PersistenceRejected.class,
                () ->
                        tx.write(
                                () -> {
                                    preferences.save(
                                            a, resolution, DIGEST, "v1", "v2", DIGEST, "{}");
                                    return null;
                                }));
        String run = intent(a, round);
        assertThrows(Exception.class, () -> intent(a, round));
        assertTrue(discovery.waiting(100).stream().anyMatch(w -> w.runId().equals(run)));
    }

    @Test
    void slotCannotBeReusedBeforePhysicalReleaseAndCrossBootReleaseFails() {
        var scope = session();
        String round = round(scope), run = intent(scope, round), execution = id();
        tx.write(
                () -> {
                    discovery.reserveExecution(
                            scope, round, run, execution, 1, "java-boot", "python-boot");
                    return null;
                });
        try {
            assertThrows(
                    Exception.class,
                    () ->
                            tx.write(
                                    () ->
                                            jdbc.update(
                                                    "UPDATE discovery.execution_slot SET state='FREE',execution_id=NULL WHERE slot_no=1")));
            assertThrows(
                    Exception.class,
                    () ->
                            tx.write(
                                    () -> {
                                        discovery.reserveExecution(
                                                scope,
                                                round,
                                                run,
                                                id(),
                                                1,
                                                "java-boot",
                                                "python-boot");
                                        return null;
                                    }));
            assertFalse(
                    tx.write(
                            () ->
                                    discovery.releaseExecution(
                                            scope,
                                            execution,
                                            "wrong",
                                            "python-boot",
                                            Instant.now())));
            assertThrows(
                    Exception.class,
                    () ->
                            tx.write(
                                    () -> {
                                        discovery.reserveExecution(
                                                scope,
                                                round,
                                                run,
                                                id(),
                                                4,
                                                "java-boot",
                                                "python-boot");
                                        return null;
                                    }));
        } finally {
            assertTrue(
                    tx.write(
                            () ->
                                    discovery.releaseExecution(
                                            scope,
                                            execution,
                                            "java-boot",
                                            "python-boot",
                                            Instant.now())));
        }
        String next = id();
        tx.write(
                () -> {
                    discovery.reserveExecution(
                            scope, round, run, next, 1, "java-boot", "python-boot");
                    return null;
                });
        assertTrue(
                tx.write(
                        () ->
                                discovery.releaseExecution(
                                        scope, next, "java-boot", "python-boot", Instant.now())));
    }

    @Test
    void twoSlotsCannotConsumeSameCandidateAndOldReviewCannotOverwrite() {
        var scope = session();
        String round = round(scope), candidate = id(), run = intent(scope, round);
        tx.write(
                () -> {
                    publication.addCandidate(
                            scope,
                            new PublicationStore.Candidate(
                                    round, candidate, UUID.randomUUID(), 1, "{}", "{}", "READY"));
                    publication.saveCard(scope, round, candidate, "{}", "PERSONALIZED", "READY");
                    return null;
                });
        assertTrue(
                tx.write(() -> publication.putSlot(scope, "left", round, candidate, "READY", -1)));
        assertThrows(
                Exception.class,
                () ->
                        tx.write(
                                () ->
                                        publication.putSlot(
                                                scope, "right", round, candidate, "READY", -1)));
        assertTrue(tx.write(() -> publication.putSlot(scope, "left", null, null, "SUPPLYING", 0)));
        assertFalse(
                tx.write(() -> publication.putSlot(scope, "left", round, candidate, "READY", 0)));
        assertTrue(
                tx.write(
                        () ->
                                publication.putLongReview(
                                        scope,
                                        round,
                                        candidate,
                                        run,
                                        "GENERATING",
                                        null,
                                        null,
                                        -1)));
        assertTrue(
                tx.write(
                        () ->
                                publication.putLongReview(
                                        scope, round, candidate, run, "READY", "{}", null, 0)));
        assertFalse(
                tx.write(
                        () ->
                                publication.putLongReview(
                                        scope, round, candidate, run, "FAILED", null, "late", 0)));
    }

    @Test
    void lockOrderAndBoundedWaitsAndExternalIoBoundary() throws Exception {
        var scope = session();
        assertThrows(
                PersistenceRejected.class,
                () ->
                        tx.write(
                                () -> {
                                    sessions.lockActive(scope);
                                    discovery.lockRuntime();
                                    return null;
                                }));
        assertThrows(
                PersistenceRejected.class,
                () ->
                        tx.write(
                                () ->
                                        ShortTransactions.outsideTransaction(
                                                () -> "network must not run")));
        assertThrows(
                PersistenceRejected.class,
                () -> sessions.recordHeard(scope, UUID.randomUUID(), Instant.now()));
        try (var c = DriverManager.getConnection(url, "be005_online", PASSWORD);
                var s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute(
                    "SELECT id FROM discovery.subject WHERE id='"
                            + scope.subjectId()
                            + "' FOR UPDATE");
            assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () ->
                            assertThrows(
                                    Exception.class,
                                    () -> tx.write(() -> sessions.lockActive(scope))));
            c.rollback();
        }
        assertEquals("3s", jdbc.queryForObject("SHOW statement_timeout", String.class));
        assertEquals("1s", jdbc.queryForObject("SHOW lock_timeout", String.class));
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () ->
                        assertThrows(
                                Exception.class,
                                () -> tx.read(() -> jdbc.queryForList("SELECT pg_sleep(10)"))));
    }

    @Test
    void audienceBindingUnknownCountsAndHotIndexes() throws Exception {
        String snapshot = id();
        UUID zero = UUID.randomUUID(), fifty = UUID.randomUUID(), unknown = UUID.randomUUID();
        tx.write(
                () -> {
                    audience.stage(
                            new AudienceStore.Snapshot(
                                    snapshot, "dataset", "all", 100, DIGEST, "a".repeat(64), "{}"));
                    audience.putCounts(snapshot, "all", 100, Map.of(zero, 0L, fifty, 50L));
                    jdbc.update(
                            "INSERT INTO discovery.audience_count SELECT ?,'all',100,md5('be005-index-'||g)::uuid,g%100 FROM generate_series(1,20000) g",
                            snapshot);
                    return null;
                });
        assertThrows(
                PersistenceRejected.class, () -> audience.counts(snapshot, "all", List.of(fifty)));
        tx.write(
                () ->
                        jdbc.update(
                                "UPDATE discovery.audience_snapshot SET active=true WHERE id=?",
                                snapshot));
        assertEquals(
                Map.of(zero, 0L, fifty, 50L),
                audience.counts(snapshot, "all", List.of(zero, fifty, unknown)));
        assertThrows(
                PersistenceRejected.class,
                () -> audience.counts(snapshot, "wrong", List.of(fifty)));
        assertThrows(
                PersistenceRejected.class,
                () ->
                        tx.write(
                                () -> {
                                    audience.putCounts(snapshot, "all", 100, Map.of(unknown, 1L));
                                    return null;
                                }));
        var indexScope = session();
        String indexRound = round(indexScope);
        tx.write(
                () -> {
                    jdbc.update(
                            "INSERT INTO discovery.discovery_intent(id,subject_id,session_id,round_id,operation,input_revision,request_digest,accepted_at,queue_deadline,status) "
                                    + "SELECT ?||g,?,?,?,'historical',1,?,now()-interval '1 day',now()-interval '1 day'+interval '30 seconds','SUCCEEDED' FROM generate_series(1,10000) g",
                            id(),
                            indexScope.subjectId(),
                            indexScope.sessionId(),
                            indexRound,
                            DIGEST);
                    jdbc.update(
                            "INSERT INTO discovery.round_candidate(session_id,round_id,candidate_id,release_group_id,rank,identity_binding,audience_binding,disposition) "
                                    + "SELECT ?,?,'idx-'||g,md5('be005-candidate-'||g)::uuid,g,'{}','{}','READY' FROM generate_series(1,1000) g",
                            indexScope.sessionId(),
                            indexRound);
                    return null;
                });
        try (var c = admin(url);
                var statement = c.createStatement()) {
            statement.execute("ANALYZE discovery.audience_count");
            statement.execute("ANALYZE discovery.discovery_intent");
            statement.execute("ANALYZE discovery.round_candidate");
        }
        tx.read(
                () -> {
                    String audiencePlan =
                            String.join(
                                    "\n",
                                    jdbc.queryForList(
                                            "EXPLAIN SELECT listeners FROM discovery.audience_count WHERE snapshot_id=? AND listening_window=? AND release_group_id=?",
                                            String.class,
                                            snapshot,
                                            "all",
                                            fifty));
                    assertTrue(audiencePlan.contains("audience_count_pkey"), audiencePlan);
                    String fifoPlan =
                            String.join(
                                    "\n",
                                    jdbc.queryForList(
                                            "EXPLAIN SELECT id FROM discovery.discovery_intent WHERE status='WAITING' ORDER BY accepted_at,id LIMIT 5",
                                            String.class));
                    assertTrue(
                            fifoPlan.contains("intent_fifo")
                                    || fifoPlan.contains("one_active_intent"),
                            fifoPlan);
                    assertFalse(fifoPlan.contains("Seq Scan on discovery_intent"), fifoPlan);
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_indexes WHERE schemaname='discovery' AND indexname='intent_fifo' AND indexdef LIKE '%(status, accepted_at, id)%'",
                                    Integer.class));
                    String candidatePlan =
                            String.join(
                                    "\n",
                                    jdbc.queryForList(
                                            "EXPLAIN SELECT candidate_id FROM discovery.round_candidate WHERE round_id=? AND disposition='READY' ORDER BY rank,candidate_id LIMIT 3",
                                            String.class,
                                            indexRound));
                    assertTrue(candidatePlan.contains("candidate_supply"), candidatePlan);
                    return null;
                });
    }
}
