package io.auralis.discovery;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.bootstrap.OnlineApplication;
import io.auralis.budget.api.BudgetAuthority;
import io.auralis.discovery.api.*;
import io.auralis.discovery.application.RoundDispatcher;
import io.auralis.persistence.*;
import io.auralis.session.api.SessionStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "AURALIS_TEST_POSTGRES_URL", matches = ".+")
class AdmissionQueueIT {
    ConfigurableApplicationContext app;
    JdbcTemplate jdbc;
    ShortTransactions tx;
    SessionStore sessions;
    DiscoveryStore discovery;
    DispatchQueue queue;
    BudgetAuthority budget;
    static final DispatchQueue.Limits LIMITS = new DispatchQueue.Limits(5, 30, 120);
    FakePython peer;
    RoundDispatcher dispatcher;
    final List<DispatchQueue.Request> inputs = new ArrayList<>();

    static String id() {
        return UUID.randomUUID().toString();
    }

    @BeforeAll
    void setup() {
        String url = System.getenv("AURALIS_TEST_POSTGRES_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        var ds =
                new DriverManagerDataSource(
                        url, "postgres", System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var admin = new JdbcTemplate(ds);
        admin.execute(
                "CREATE TABLE IF NOT EXISTS public.bootstrap_probe(id bigint primary key,value varchar(255))");
        admin.execute("CREATE ROLE be010_online LOGIN PASSWORD 'be010-synthetic-password'");
        admin.execute("GRANT USAGE ON SCHEMA discovery,catalog,ingestion TO be010_online");
        admin.execute(
                "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO be010_online");
        admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA catalog,ingestion TO be010_online");
        admin.execute("GRANT SELECT ON public.flyway_schema_history TO be010_online");
        admin.execute("GRANT SELECT,INSERT,DELETE ON public.bootstrap_probe TO be010_online");
        boot();
    }

    void boot() {
        boot(false);
    }

    void boot(boolean web) {
        var start = new SpringApplication(OnlineApplication.class);
        start.setWebApplicationType(web ? WebApplicationType.SERVLET : WebApplicationType.NONE);
        start.setDefaultProperties(
                Map.of(
                        "auralis.jdbc-url",
                        System.getenv("AURALIS_TEST_POSTGRES_URL"),
                        "auralis.database-user",
                        "be010_online",
                        "auralis.database-password",
                        "be010-synthetic-password",
                        "auralis.product-origin",
                        "http://127.0.0.1:8080",
                        "auralis.python-url",
                        "http://127.0.0.1:1",
                        "auralis.service-token",
                        "be010-synthetic-service-token-12345678",
                        "auralis.contract-revision",
                        "0.32"));
        app =
                start.run(
                        "--auralis.subject-secret=be010-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789",
                        "--auralis.allow-insecure-cookie=true",
                        "--auralis.session-cleanup-enabled=false",
                        "--server.port=0");
        jdbc = app.getBean(JdbcTemplate.class);
        tx = app.getBean(ShortTransactions.class);
        sessions = app.getBean(SessionStore.class);
        discovery = app.getBean(DiscoveryStore.class);
        queue = app.getBean(DispatchQueue.class);
        budget = app.getBean(BudgetAuthority.class);
    }

    @BeforeEach
    void before() {
        peer = new FakePython();
        dispatcher = new RoundDispatcher(queue, peer, LIMITS, "java-1", Clock.systemUTC());
        dispatcher.tick();
    }

    @AfterEach
    void cleanup() {
        for (var job : queue.active()) queue.release(job, "INTERRUPTED");
        for (var input : inputs) {
            jdbc.update(
                    "UPDATE discovery.discovery_intent SET status='CANCELLED',revision=revision+1 WHERE session_id=? AND status IN ('WAITING','DISPATCHING','RUNNING')",
                    input.scope().sessionId());
            jdbc.update(
                    "DELETE FROM discovery.dispatch_command WHERE session_id=?",
                    input.scope().sessionId());
        }
        inputs.clear();
        jdbc.update(
                "UPDATE discovery.runtime_control SET coordinator_boot=NULL,python_boot=NULL,maintenance=true,revision=revision+1 WHERE singleton");
    }

    @AfterAll
    void close() {
        if (app != null) app.close();
    }

    DispatchQueue.Request input() throws Exception {
        String subject = id(), session = id(), round = id();
        var base =
                (ObjectNode)
                        JSON.readTree(
                                        Path.of(System.getProperty("basedir"))
                                                .resolveSibling(
                                                        "intelligence/tests/fixtures/agent-contract-cases.json")
                                                .toFile())
                                .get(0)
                                .path("message")
                                .path("payload")
                                .deepCopy();
        tx.write(
                () -> {
                    sessions.createSubject(
                            subject,
                            "sha256:" + subject.replace("-", "").repeat(2),
                            Instant.now(),
                            Instant.now().plusSeconds(3600));
                    sessions.createSession(
                            session,
                            subject,
                            1,
                            base.path("input").path("raw").toString(),
                            Instant.now(),
                            Instant.now().plusSeconds(3600));
                    return null;
                });
        var scope = sessions.current(subject).orElseThrow().scope();
        tx.write(
                () -> {
                    discovery.createRound(
                            scope, round, 1, "{}", "{}", Instant.now().plusSeconds(600));
                    return null;
                });
        base.put("session_id", session)
                .put("round_id", round)
                .put("run_kind", "INITIAL")
                .put("erasure_handle", id());
        ((ObjectNode) base.path("deadlines"))
                .put("round_deadline_at", Instant.now().plusSeconds(600).toString());
        var b = (ObjectNode) base.path("budget");
        b.put("session_budget_scope_id", id()).put("round_budget_scope_id", id());
        var limits = (ObjectNode) b.path("limits");
        limits.put("session_cap_microusd", 1000000).put("round_cap_microusd", 1000000);
        limits.putObject("lane_caps").put("FOREGROUND", 500000).put("BACKGROUND", 500000);
        base.path("model_bindings")
                .forEach(
                        v ->
                                ((ObjectNode) v)
                                        .put("max_input_tokens", 100)
                                        .put("max_output_tokens", 100));
        var prices = new HashMap<String, BudgetAuthority.Price>();
        base.path("model_bindings")
                .fields()
                .forEachRemaining(
                        e -> {
                            var v = e.getValue();
                            prices.put(
                                    v.path("ref").asText(),
                                    new BudgetAuthority.Price(
                                            v.path("price_revision").asText(),
                                            1000000,
                                            1000000,
                                            1000000,
                                            1,
                                            v.path("max_input_tokens").asLong(),
                                            v.path("max_output_tokens").asLong()));
                        });
        base.put("payload_digest", contentDigest(base, "payload_digest"));
        var r =
                new DispatchQueue.Request(
                        scope,
                        round,
                        "INITIAL",
                        id(),
                        base.toString(),
                        prices,
                        1,
                        Map.of(),
                        Set.of());
        inputs.add(r);
        return r;
    }

    static final class FakePython implements PythonClient {
        String boot = "python-1";
        int capacity = 3;
        boolean lost, offline, slowCancel, neverAccepted, unknown;
        final Map<String, ObjectNode> starts = new ConcurrentHashMap<>(),
                views = new ConcurrentHashMap<>();
        final Map<String, Integer> sends = new ConcurrentHashMap<>();
        final List<String> cancelled = new ArrayList<>();

        void healthy() {
            ShortTransactions.outsideTransaction(() -> null);
            if (offline) throw new Unavailable();
        }

        public String command(String execution, String command, String payload) {
            return "{\"accepted\":true}";
        }

        public String runtime() {
            healthy();
            var p =
                    JSON.createObjectNode()
                            .put("boot_id", boot)
                            .put("status", "READY")
                            .put("execution_capacity", capacity);
            var a = p.putArray("active");
            views.values().stream()
                    .filter(v -> !v.path("slot_released").asBoolean())
                    .forEach(a::add);
            return p.toString();
        }

        static ObjectNode view(JsonNode p) {
            var v =
                    JSON.createObjectNode()
                            .put("execution_id", p.path("execution_id").asText())
                            .put("round_id", p.path("round_id").asText())
                            .put("boot_id", p.path("boot").path("python_boot_id").asText())
                            .put("status", "RUNNING")
                            .put("phase", "BACKGROUND")
                            .put("foreground", false)
                            .put("slot_released", false)
                            .put("last_delivery_seq", 0)
                            .put("undelivered_count", 0)
                            .putNull("lanes")
                            .put("updated_seq", 1);
            v.putArray("uncertain_invocations");
            var c = v.putObject("counters");
            for (String key :
                    List.of(
                            "researched",
                            "qualified",
                            "shortlisted",
                            "ready",
                            "long_ready",
                            "long_generating")) c.put(key, 0);
            return v;
        }

        public String start(String execution, String body) {
            healthy();
            sends.merge(execution, 1, Integer::sum);
            if (neverAccepted) throw new Unavailable();
            try {
                var p = (ObjectNode) JSON.readTree(body);
                starts.putIfAbsent(execution, p);
                views.putIfAbsent(execution, view(p));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            if (lost) {
                lost = false;
                throw new Unavailable();
            }
            return views.get(execution).toString();
        }

        public String admission(String execution, String python, String digest, String deadline) {
            healthy();
            var v = views.get(execution);
            var r =
                    JSON.createObjectNode()
                            .put("execution_id", execution)
                            .put("boot_id", boot)
                            .put("payload_digest", digest)
                            .put(
                                    "status",
                                    unknown
                                            ? "UNKNOWN"
                                            : v == null
                                                    ? "NOT_ACCEPTED"
                                                    : v.path("slot_released").asBoolean()
                                                            ? "TERMINAL"
                                                            : "ACTIVE");
            if (unknown) r.putNull("dedup_until");
            else r.put("dedup_until", deadline);
            r.set("execution", v == null ? JSON.nullNode() : v);
            return r.toString();
        }

        public String cancel(String execution, String coordinator, String python, String reason) {
            healthy();
            cancelled.add(execution);
            var v = views.get(execution);
            v.put("status", slowCancel ? "CLOSING" : "CANCELLED")
                    .put("slot_released", !slowCancel)
                    .put("updated_seq", v.path("updated_seq").asInt() + 1);
            return v.toString();
        }

        void complete(String execution) {
            views.get(execution)
                    .put("status", "CLOSED")
                    .put("slot_released", true)
                    .put("updated_seq", 10);
        }
    }

    @Test
    void busyRuntimeDefersWithoutMaintenanceOrResend() throws Exception {
        queue.accept(input(), LIMITS);
        dispatcher.tick();
        var job = queue.active().getFirst();
        try (var connection = app.getBean(javax.sql.DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement
                        .executeQuery(
                                "SELECT singleton FROM discovery.runtime_control WHERE singleton FOR UPDATE")
                        .close();
            }
            assertDoesNotThrow(dispatcher::tick);
            assertTrue(queue.ready("java-1"));
            assertEquals(1, peer.sends.get(job.execution()));
            assertEquals(1, queue.active().size());
            connection.rollback();
        }
        peer.complete(job.execution());
        dispatcher.tick();
        assertTrue(queue.active().isEmpty());
        assertEquals(1, peer.sends.get(job.execution()));
    }

    @Test
    void busySubjectDefersClaimWithoutMaintenanceOrLosingQueuedRun() throws Exception {
        var in = input();
        var accepted = queue.accept(in, LIMITS);
        try (var connection = app.getBean(javax.sql.DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "SELECT id FROM discovery.subject WHERE id=? FOR UPDATE")) {
                statement.setString(1, in.scope().subjectId());
                statement.executeQuery().close();
            }
            assertDoesNotThrow(dispatcher::tick);
            assertTrue(queue.ready("java-1"));
            assertTrue(peer.sends.isEmpty());
            assertTrue(queue.active().isEmpty());
            assertEquals(
                    "WAITING",
                    jdbc.queryForObject(
                            "SELECT status FROM discovery.discovery_intent WHERE id=?",
                            String.class,
                            accepted.run()));
            connection.rollback();
        }
        dispatcher.tick();
        assertEquals("RUNNING", queue.status(in.scope(), accepted.run()).status());
        assertEquals(1, peer.sends.values().iterator().next());
    }

    @Test
    void busyExecutionObservationDefersWithoutMaintenanceOrResend() throws Exception {
        queue.accept(input(), LIMITS);
        dispatcher.tick();
        var job = queue.active().getFirst();
        var source = app.getBean(javax.sql.DataSource.class);
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "SELECT id FROM discovery.execution WHERE id=? FOR UPDATE")) {
                statement.setString(1, job.execution());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            dispatcher.tick();
            assertTrue(queue.ready("java-1"));
            assertEquals(1, peer.sends.get(job.execution()));
            assertEquals(1, queue.active().size());
            connection.rollback();
        }
        peer.complete(job.execution());
        dispatcher.tick();
        assertTrue(queue.active().isEmpty());
        assertEquals(1, peer.sends.get(job.execution()));
    }

    @Test
    void unknownAdmissionStillAllowsCancellationButNeverResend() throws Exception {
        var in = input();
        var accepted = queue.accept(in, LIMITS);
        dispatcher.tick();
        peer.unknown = true;
        peer.slowCancel = true;
        queue.cancel(in.scope(), accepted.run());
        dispatcher.tick();
        assertEquals(1, peer.cancelled.size());
        assertEquals(1, queue.active().size());
        assertEquals(1, peer.sends.values().iterator().next());
        peer.slowCancel = false;
        dispatcher.tick();
        assertTrue(queue.active().isEmpty());
    }

    @Test
    void legacyWaitingWithoutDispatchPayloadIsInterruptedRatherThanReconstructed()
            throws Exception {
        var in = input();
        String legacy = id();
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    discovery.createIntent(
                            in.scope(),
                            in.round(),
                            legacy,
                            "legacy",
                            1,
                            "sha256:" + "a".repeat(64),
                            Instant.now(),
                            Instant.now().plusSeconds(30));
                    return null;
                });
        var restarted = new RoundDispatcher(queue, peer, LIMITS, "java-new", Clock.systemUTC());
        restarted.tick();
        assertEquals("INTERRUPTED", queue.status(in.scope(), legacy).status());
        assertTrue(peer.sends.isEmpty());
        var newRun = queue.accept(in, LIMITS);
        restarted.tick();
        assertEquals("RUNNING", queue.status(in.scope(), newRun.run()).status());
    }

    @Test
    void deploymentCanTightenCapacityOnlyWhenBothServicesAgree() throws Exception {
        var reduced = new DispatchQueue.Limits(2, 5, 30, 120);
        var loop = new RoundDispatcher(queue, peer, reduced, "java-2", Clock.systemUTC());
        loop.tick();
        var first = input();
        assertThrows(PersistenceRejected.class, () -> queue.accept(first, reduced));
        peer.capacity = 2;
        loop.tick();
        queue.accept(first, reduced);
        loop.tick();
        queue.accept(input(), reduced);
        loop.tick();
        var third = input();
        var pending = queue.accept(third, reduced);
        loop.tick();
        assertEquals(2, queue.active().size());
        assertEquals("WAITING", queue.status(third.scope(), pending.run()).status());
    }

    @Test
    void scheduledDispatcherOwnsOneDatabaseLeaseAndRunsAutomatically() throws Exception {
        var source = app.getBean(javax.sql.DataSource.class);
        try (var lifecycle =
                new io.auralis.bootstrap.DispatchLifecycle(
                        source, queue, peer, Clock.systemUTC(), 3, 5, 30, 120)) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            new io.auralis.bootstrap.DispatchLifecycle(
                                    source, queue, peer, Clock.systemUTC(), 3, 5, 30, 120));
            await(
                    () ->
                            !jdbc.queryForObject(
                                    "SELECT maintenance FROM discovery.runtime_control WHERE singleton",
                                    Boolean.class));
            for (int n = 0; n < 3; n++) queue.accept(input(), LIMITS);
            await(() -> queue.active().size() == 3 && peer.sends.size() == 3);
            var fourth = input();
            var waiting = queue.accept(fourth, LIMITS);
            peer.complete(queue.active().getFirst().execution());
            await(() -> queue.active().stream().anyMatch(j -> j.run().equals(waiting.run())));
            assertEquals(3, queue.active().size());
        }
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void invalidQueuedPlanRollsBackSlotAndDoesNotBlockNextUser() throws Exception {
        var in = input();
        var bad =
                new DispatchQueue.Request(
                        in.scope(),
                        in.round(),
                        in.operation(),
                        id(),
                        in.template(),
                        Map.of(),
                        in.catalogDataset(),
                        in.candidates(),
                        in.demands());
        var rejected = queue.accept(bad, LIMITS);
        dispatcher.tick();
        assertEquals("FAILED", queue.status(in.scope(), rejected.run()).status());
        assertTrue(queue.active().isEmpty());
        var good = input();
        var accepted = queue.accept(good, LIMITS);
        dispatcher.tick();
        assertEquals("RUNNING", queue.status(good.scope(), accepted.run()).status());
    }

    @Test
    void technicalDeadlineBlocksNewCallsBeforePhysicalCancellation() throws Exception {
        var shortLimits = new DispatchQueue.Limits(5, 1, 2);
        var loop = new RoundDispatcher(queue, peer, shortLimits, "java-1", Clock.systemUTC());
        loop.tick();
        var in = input();
        var accepted = queue.accept(in, shortLimits);
        loop.tick();
        Thread.sleep(2100);
        peer.slowCancel = true;
        loop.tick();
        assertEquals("EXPIRED", queue.status(in.scope(), accepted.run()).status());
        assertEquals(1, queue.active().size());
        peer.slowCancel = false;
        loop.tick();
        assertTrue(queue.active().isEmpty());
    }

    @Test
    void productionControlRoutesUsePersistedOwnershipAndAcceptLateUsage() throws Exception {
        app.close();
        boot(true);
        dispatcher = new RoundDispatcher(queue, peer, LIMITS, "java-1", Clock.systemUTC());
        dispatcher.tick();
        var in = input();
        var accepted = queue.accept(in, LIMITS);
        dispatcher.tick();
        var job = queue.active().getFirst();
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        String model =
                JSON.readTree(job.payload())
                        .path("model_bindings")
                        .elements()
                        .next()
                        .path("ref")
                        .asText();
        String script =
                """
            import asyncio,sys
            from auralis_intelligence.agents.http import ControlHTTPClient
            from auralis_intelligence.contracts import ContractError
            async def run():
                base,execution,run,model,phase=sys.argv[1:]
                client=ControlHTTPClient(base,'be010-synthetic-service-token-12345678')
                p={'invocation_id':execution+':http','attempt':1,'purpose':'SELECT','cost_class':'MODEL','lane':'FOREGROUND','run_id':run,'candidate_id':None,'read_ids':[],'model_binding_ref':model,'max_input_tokens':10,'max_output_tokens':10,'input_digest':'sha256:'+'f'*64}
                try:
                    if phase=='grant':
                        assert (await client.request(execution,'invocation-grants',{'items':[p]}))['items'][0]['decision']=='GRANTED'
                    else:
                        receipt={'invocation_id':p['invocation_id'],'receipt_id':execution+':receipt','outcome':'COMPLETED','finish':'COMPLETE','input_tokens':2,'cached_input_tokens':0,'output_tokens':3,'provider_request_ref':None}
                        result=await client.request(execution,'usage-receipt',receipt)
                        assert result['status']=='SETTLED'
                        assert result==await client.request(execution,'usage-receipt',receipt)
                        p['invocation_id']+='-new'
                        try:await client.request(execution,'invocation-grants',{'items':[p]})
                        except ContractError as e:assert str(e)=='EXECUTION_GONE',str(e)
                        else:raise AssertionError('cancelled execution received send right')
                        assert (await client.request(execution,'budget-state',{}))['lanes']['FOREGROUND']['settled_microusd']==5
                finally:await client.close()
            asyncio.run(run())
            """;
        try {
            for (String phase : List.of("grant", "late")) {
                if (phase.equals("late")) queue.cancel(in.scope(), accepted.run());
                var command =
                        new ProcessBuilder(
                                System.getenv("AURALIS_RECALL_PYTHON"),
                                "-c",
                                script,
                                base,
                                job.execution(),
                                job.run(),
                                model,
                                phase);
                command.environment()
                        .put(
                                "PYTHONPATH",
                                Path.of(System.getProperty("basedir"))
                                        .resolveSibling("intelligence/src")
                                        .toString());
                command.inheritIO();
                var child = command.start();
                assertTrue(child.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            }
        } finally {
            app.close();
            boot();
        }
    }

    @Test
    void realPythonAndPostgresAutomaticallyAdmitFourthAfterCancellation() throws Exception {
        try (var real = new PythonClientIT.ProcessPeer("java-real")) {
            var loop =
                    new RoundDispatcher(queue, real.client, LIMITS, "java-real", Clock.systemUTC());
            loop.tick();
            var first = input();
            var firstRun = queue.accept(first, LIMITS);
            loop.tick();
            for (int n = 0; n < 2; n++) {
                queue.accept(input(), LIMITS);
                loop.tick();
            }
            var fourth = input();
            var waiting = queue.accept(fourth, LIMITS);
            assertEquals("WAITING", waiting.status());
            queue.cancel(first.scope(), firstRun.run());
            loop.tick();
            assertEquals(3, queue.active().size());
            assertEquals("RUNNING", queue.status(fourth.scope(), waiting.run()).status());
            assertEquals(3, JSON.readTree(real.client.runtime()).path("active").size());
        }
    }

    @Test
    void supplyCommandsMergeTargetsWithoutExtendingWaitAndRollbackWithCaller() throws Exception {
        var in = input();
        var template = (ObjectNode) JSON.readTree(in.template());
        template.put("run_kind", "SUPPLY");
        template.put("payload_digest", contentDigest(template, "payload_digest"));
        var a =
                new DispatchQueue.Request(
                        in.scope(),
                        in.round(),
                        "SUPPLY",
                        id(),
                        template.toString(),
                        in.prices(),
                        in.catalogDataset(),
                        in.candidates(),
                        Set.of("slot-a"));
        var b =
                new DispatchQueue.Request(
                        in.scope(),
                        in.round(),
                        "SUPPLY",
                        id(),
                        template.toString(),
                        in.prices(),
                        in.catalogDataset(),
                        in.candidates(),
                        Set.of("slot-b"));
        var accepted = queue.accept(a, LIMITS);
        var merged = queue.accept(b, LIMITS);
        assertEquals(accepted.run(), merged.run());
        assertEquals(accepted.queueDeadline(), merged.queueDeadline());
        assertEquals(Set.of("slot-a", "slot-b"), queue.demands(in.scope(), accepted.run()));
        assertEquals(merged.run(), queue.accept(b, LIMITS).run());
        var conflict =
                new DispatchQueue.Request(
                        b.scope(),
                        b.round(),
                        b.operation(),
                        b.command(),
                        b.template(),
                        b.prices(),
                        b.catalogDataset(),
                        b.candidates(),
                        Set.of("slot-c"));
        assertThrows(PersistenceRejected.class, () -> queue.accept(conflict, LIMITS));
        var independent = input();
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.write(
                                () -> {
                                    queue.accept(independent, LIMITS);
                                    throw new IllegalStateException("ROLLBACK");
                                }));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.dispatch_command WHERE session_id=?",
                        Integer.class,
                        independent.scope().sessionId()));
    }

    @Test
    void replacementBlocksOldGrantsAndWaitsForItsPhysicalRelease() throws Exception {
        var in = input();
        var accepted = queue.accept(in, LIMITS);
        dispatcher.tick();
        var old = queue.active().getFirst();
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.close(in.scope(), "SUPERSEDED");
                    app.getBean(SessionExecutions.class)
                            .invalidate(List.of(in.scope().sessionId()), "SUPERSEDED");
                    return null;
                });
        assertThrows(
                PersistenceRejected.class,
                () -> budget.grants(queue.context(old.execution()).budget(), "{\"items\":[]}"));
        peer.slowCancel = true;
        dispatcher.tick();
        assertEquals(1, queue.active().size());
        peer.slowCancel = false;
        dispatcher.tick();
        assertTrue(queue.active().isEmpty());
        assertEquals(
                "SUPERSEDED",
                jdbc.queryForObject(
                        "SELECT status FROM discovery.discovery_intent WHERE id=?",
                        String.class,
                        accepted.run()));
    }

    @Test
    void publicationOutcomeSurvivesPhysicalCloseAndRecoveryBlocksGrants() throws Exception {
        var in = input();
        var accepted = queue.accept(in, LIMITS);
        dispatcher.tick();
        var job = queue.active().getFirst();
        queue.unavailable("java-1");
        assertThrows(
                PersistenceRejected.class,
                () -> budget.grants(queue.context(job.execution()).budget(), "{\"items\":[]}"));
        dispatcher.tick();
        queue.complete(in.scope(), accepted.run(), "SUCCEEDED");
        peer.complete(job.execution());
        dispatcher.tick();
        assertEquals("SUCCEEDED", queue.status(in.scope(), accepted.run()).status());
    }

    @Test
    void threeSlotsFifoAndBoundedWaiting() throws Exception {
        var runs = new ArrayList<DispatchQueue.Accepted>();
        for (int i = 0; i < 3; i++) {
            runs.add(queue.accept(input(), LIMITS));
            dispatcher.tick();
        }
        assertEquals(3, queue.active().size());
        for (int i = 0; i < 5; i++) runs.add(queue.accept(input(), LIMITS));
        var rejected = input();
        assertThrows(PersistenceRejected.class, () -> queue.accept(rejected, LIMITS));
        assertEquals(
                8,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.dispatch_command", Integer.class));
        String done = queue.active().getFirst().execution();
        peer.complete(done);
        dispatcher.tick();
        assertEquals(3, queue.active().size());
        assertTrue(queue.active().stream().anyMatch(j -> j.run().equals(runs.get(3).run())));
        assertTrue(peer.cancelled.isEmpty());
        assertNotNull(queue.accept(rejected, LIMITS));
    }

    @Test
    void concurrentAcceptAndClaimsNeverDoubleAssign() throws Exception {
        var inputs = List.of(input(), input(), input(), input(), input());
        try (var pool = Executors.newFixedThreadPool(5)) {
            var futures = new ArrayList<Future<DispatchQueue.Accepted>>();
            for (var in : inputs) futures.add(pool.submit(() -> queue.accept(in, LIMITS)));
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
            var jobs = new ArrayList<Future<DispatchQueue.Job>>();
            for (int n = 0; n < 5; n++) jobs.add(pool.submit(() -> queue.claim("java-1", LIMITS)));
            int count = 0;
            var ids = new HashSet<String>();
            for (var f : jobs) {
                var j = f.get(10, TimeUnit.SECONDS);
                if (j != null) {
                    count++;
                    assertTrue(ids.add(j.execution()));
                }
            }
            assertEquals(3, count);
            assertEquals(3, queue.active().size());
        }
    }

    @Test
    void lostAcceptanceResponseQueriesOriginalWithoutResending() throws Exception {
        var in = input();
        var accepted = queue.accept(in, LIMITS);
        peer.lost = true;
        dispatcher.tick();
        assertEquals(1, peer.sends.values().stream().mapToInt(Integer::intValue).sum());
        dispatcher.tick();
        assertEquals("RUNNING", queue.status(in.scope(), accepted.run()).status());
        assertEquals(1, peer.sends.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(accepted.run(), queue.accept(in, LIMITS).run());
    }

    @Test
    void onlyProvenNotAcceptedCanRetryTwiceAndUnknownKeepsSlot() throws Exception {
        queue.accept(input(), LIMITS);
        peer.neverAccepted = true;
        dispatcher.tick();
        peer.unknown = true;
        dispatcher.tick();
        assertEquals(1, peer.sends.values().iterator().next());
        peer.unknown = false;
        dispatcher.tick();
        assertEquals(2, peer.sends.values().iterator().next());
        dispatcher.tick();
        assertTrue(queue.active().isEmpty());
    }

    @Test
    void cancellationWaitsForPhysicalCleanupWithoutTouchingOtherUsers() throws Exception {
        var first = input();
        var a = queue.accept(first, LIMITS);
        dispatcher.tick();
        var second = input();
        queue.accept(second, LIMITS);
        dispatcher.tick();
        peer.slowCancel = true;
        queue.cancel(first.scope(), a.run());
        dispatcher.tick();
        assertEquals(2, queue.active().size());
        assertEquals(1, peer.cancelled.stream().distinct().count());
        assertEquals("CANCELLED", queue.status(first.scope(), a.run()).status());
        peer.slowCancel = false;
        dispatcher.tick();
        assertEquals(1, queue.active().size());
        assertEquals(second.scope().sessionId(), queue.active().getFirst().scope().sessionId());
    }

    @Test
    void javaRestartInterruptsOldExecutionAndRestoresUnsentQueue() throws Exception {
        var running = input();
        var r = queue.accept(running, LIMITS);
        dispatcher.tick();
        var waiting = input();
        var w = queue.accept(waiting, LIMITS);
        app.close();
        boot();
        var restarted = new RoundDispatcher(queue, peer, LIMITS, "java-2", Clock.systemUTC());
        restarted.tick();
        restarted.tick();
        assertEquals("INTERRUPTED", queue.status(running.scope(), r.run()).status());
        assertEquals("RUNNING", queue.status(waiting.scope(), w.run()).status());
        assertEquals(2, peer.sends.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void pythonRestartDoesNotReplayPaidExecution() throws Exception {
        var in = input();
        var run = queue.accept(in, LIMITS);
        dispatcher.tick();
        peer.boot = "python-2";
        peer.views.clear();
        peer.starts.clear();
        dispatcher.tick();
        assertTrue(queue.active().isEmpty());
        assertEquals("INTERRUPTED", queue.status(in.scope(), run.run()).status());
        assertEquals(1, peer.sends.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void unavailablePeerBlocksNewAdmissionAndRetainsSlots() throws Exception {
        queue.accept(input(), LIMITS);
        dispatcher.tick();
        peer.offline = true;
        dispatcher.tick();
        var in = input();
        assertThrows(PersistenceRejected.class, () -> queue.accept(in, LIMITS));
        assertEquals(1, queue.active().size());
        peer.offline = false;
        dispatcher.tick();
        assertNotNull(queue.accept(in, LIMITS));
    }

    @Test
    void queueExpiryKeepsOriginalDeadlineAndConsumesNoBudget() throws Exception {
        var in = input();
        var accepted = queue.accept(in, new DispatchQueue.Limits(5, 1, 120));
        Thread.sleep(1100);
        dispatcher.tick();
        assertEquals("EXPIRED", queue.status(in.scope(), accepted.run()).status());
        assertTrue(peer.sends.isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.budget_scope WHERE session_id=?",
                        Integer.class,
                        in.scope().sessionId()));
    }
}
