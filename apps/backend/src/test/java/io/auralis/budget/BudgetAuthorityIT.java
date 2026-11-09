package io.auralis.budget;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.bootstrap.OnlineApplication;
import io.auralis.budget.api.BudgetAuthority;
import io.auralis.budget.api.BudgetAuthority.Context;
import io.auralis.discovery.api.DiscoveryStore;
import io.auralis.evidence.api.EvidenceAuthority;
import io.auralis.persistence.*;
import io.auralis.session.api.SessionStore;
import java.time.Instant;
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
class BudgetAuthorityIT {
    ConfigurableApplicationContext app;
    JdbcTemplate jdbc;
    ShortTransactions tx;
    SessionStore sessions;
    DiscoveryStore discovery;
    BudgetAuthority budget;
    final List<Context> active = new ArrayList<>();

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
        admin.execute("CREATE ROLE be002_online LOGIN PASSWORD 'be002-synthetic-password'");
        admin.execute("GRANT USAGE ON SCHEMA discovery,catalog,ingestion TO be002_online");
        admin.execute(
                "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO be002_online");
        admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA catalog,ingestion TO be002_online");
        admin.execute("GRANT SELECT ON public.flyway_schema_history TO be002_online");
        admin.execute("GRANT SELECT,INSERT,DELETE ON public.bootstrap_probe TO be002_online");
        boot();
    }

    void boot() {
        var start = new SpringApplication(OnlineApplication.class);
        start.setWebApplicationType(WebApplicationType.NONE);
        start.setDefaultProperties(
                Map.of(
                        "auralis.jdbc-url",
                        System.getenv("AURALIS_TEST_POSTGRES_URL"),
                        "auralis.database-user",
                        "be002_online",
                        "auralis.database-password",
                        "be002-synthetic-password",
                        "auralis.product-origin",
                        "http://127.0.0.1:8080",
                        "auralis.python-url",
                        "http://127.0.0.1:1",
                        "auralis.service-token",
                        "be002-synthetic-service-token-12345678",
                        "auralis.contract-revision",
                        "0.32"));
        app =
                start.run(
                        "--auralis.subject-secret=be002-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789",
                        "--auralis.allow-insecure-cookie=true",
                        "--auralis.session-cleanup-enabled=false");
        jdbc = app.getBean(JdbcTemplate.class);
        tx = app.getBean(ShortTransactions.class);
        sessions = app.getBean(SessionStore.class);
        discovery = app.getBean(DiscoveryStore.class);
        budget = app.getBean(BudgetAuthority.class);
    }

    @AfterAll
    void close() {
        if (app != null) app.close();
    }

    @AfterEach
    void release() {
        for (var c : active) {
            tx.write(
                    () ->
                            discovery.releaseExecution(
                                    c.evidence().scope(),
                                    c.evidence().execution(),
                                    "java",
                                    "python",
                                    Instant.now()));
        }
        active.clear();
    }

    Context context(long sessionCap, long roundCap, long fg, long bg, int calls) {
        String subject = id(), session = id(), round = id(), run = id(), execution = id();
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
                            "{\"liked_albums\":[]}",
                            Instant.now(),
                            Instant.now().plusSeconds(3600));
                    return null;
                });
        var scope = sessions.current(subject).orElseThrow().scope();
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    discovery.createRound(
                            scope, round, 1, "{}", "{}", Instant.now().plusSeconds(600));
                    discovery.createIntent(
                            scope,
                            round,
                            run,
                            "test",
                            1,
                            "sha256:" + "a".repeat(64),
                            Instant.now(),
                            Instant.now().plusSeconds(600));
                    int slot =
                            jdbc.queryForObject(
                                    "SELECT min(slot_no) FROM discovery.execution_slot WHERE state='FREE'",
                                    Integer.class);
                    discovery.reserveExecution(
                            scope, round, run, execution, slot, "java", "python");
                    return null;
                });
        var e =
                new EvidenceAuthority.Context(
                        scope,
                        round,
                        execution,
                        "java",
                        "python",
                        1,
                        100,
                        Map.of("candidate", "00000000-0000-0000-0000-000000000001"),
                        false);
        var c =
                new Context(
                        e,
                        run,
                        id(),
                        id(),
                        sessionCap,
                        roundCap,
                        fg,
                        bg,
                        calls,
                        10,
                        Map.of(
                                "model-" + id(),
                                new BudgetAuthority.Price(
                                        "test-v1", 1000000, 500000, 2000000, 7, 10000, 10000)));
        active.add(c);
        budget.register(c);
        return c;
    }

    ObjectNode plan(Context c, String suffix, String lane, String purpose) {
        var p =
                JSON.createObjectNode()
                        .put("invocation_id", c.evidence().execution() + ":" + suffix)
                        .put("attempt", 1)
                        .put("purpose", purpose)
                        .put("cost_class", purpose.equals("SEARCH") ? "TOOL_SERVICE" : "MODEL")
                        .put("lane", lane)
                        .put("run_id", c.run())
                        .putNull("candidate_id")
                        .put("model_binding_ref", c.prices().keySet().iterator().next())
                        .put("max_input_tokens", 10)
                        .put("max_output_tokens", 10)
                        .put("input_digest", "sha256:" + "b".repeat(64));
        p.putArray("read_ids");
        return p;
    }

    JsonNode grants(Context c, ObjectNode... plans) throws Exception {
        var p = JSON.createObjectNode();
        var a = p.putArray("items");
        for (var item : plans) a.add(item);
        return JSON.readTree(budget.grants(c, p.toString())).path("items");
    }

    ObjectNode receipt(ObjectNode p, String rid, String outcome) {
        return JSON.createObjectNode()
                .put("invocation_id", p.path("invocation_id").asText())
                .put("receipt_id", rid)
                .put("outcome", outcome)
                .put("finish", "COMPLETE")
                .put("input_tokens", outcome.equals("NOT_PROCESSED") ? 0 : 4)
                .put("cached_input_tokens", outcome.equals("NOT_PROCESSED") ? 0 : 2)
                .put("output_tokens", outcome.equals("NOT_PROCESSED") ? 0 : 3)
                .putNull("provider_request_ref");
    }

    JsonNode state(Context c) throws Exception {
        return JSON.readTree(budget.state(c));
    }

    @Test
    void backgroundContinuationWithoutRunKeepsRoundRepairQuota() throws Exception {
        var c = context(1000, 1000, 500, 500, 10);
        var background =
                new Context(
                        c.evidence(),
                        null,
                        c.sessionScope(),
                        c.roundScope(),
                        1000,
                        1000,
                        500,
                        500,
                        10,
                        10,
                        c.prices());
        var first = plan(background, "bg-null", "BACKGROUND", "STRUCTURE_REPAIR");
        assertEquals("GRANTED", grants(background, first).get(0).path("decision").asText());
        assertEquals(
                "REPAIR_QUOTA_EXHAUSTED",
                grants(background, plan(background, "bg-next", "BACKGROUND", "STRUCTURE_REPAIR"))
                        .get(0)
                        .path("deny_reason")
                        .asText());
    }

    @Test
    void missingCachedUsageRetainsMaximumReservation() throws Exception {
        var c = context(1000, 1000, 500, 500, 10);
        var p = plan(c, "cache", "FOREGROUND", "SELECT");
        grants(c, p);
        var r = receipt(p, "partial-cache", "COMPLETED").putNull("cached_input_tokens");
        assertEquals(
                "UNCERTAIN",
                JSON.readTree(budget.settle(c.evidence().execution(), r.toString()))
                        .path("status")
                        .asText());
        assertEquals(
                30, state(c).path("lanes").path("FOREGROUND").path("reserved_microusd").asLong());
        assertTrue(state(c).path("cached_input_tokens").isNull());
        r.put("receipt_id", "known-cache").put("cached_input_tokens", 2);
        assertEquals(
                "SETTLED",
                JSON.readTree(budget.settle(c.evidence().execution(), r.toString()))
                        .path("status")
                        .asText());
    }

    @Test
    void laterRoundStillConsumesOriginalSessionBudget() throws Exception {
        var first = context(70, 50, 50, 0, 10);
        grants(first, plan(first, "first", "FOREGROUND", "SELECT"));
        release();
        var scope = first.evidence().scope();
        tx.write(() -> discovery.transitionRun(scope, first.run(), 0, "WAITING", "SUCCEEDED"));
        String round = id(), run = id(), execution = id();
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    discovery.createRound(
                            scope, round, 2, "{}", "{}", Instant.now().plusSeconds(600));
                    discovery.createIntent(
                            scope,
                            round,
                            run,
                            "test",
                            1,
                            "sha256:" + "a".repeat(64),
                            Instant.now(),
                            Instant.now().plusSeconds(600));
                    int slot =
                            jdbc.queryForObject(
                                    "SELECT min(slot_no) FROM discovery.execution_slot WHERE state='FREE'",
                                    Integer.class);
                    discovery.reserveExecution(
                            scope, round, run, execution, slot, "java", "python");
                    return null;
                });
        var e =
                new EvidenceAuthority.Context(
                        scope, round, execution, "java", "python", 1, 100, Map.of(), false);
        var next =
                new Context(
                        e, run, first.sessionScope(), id(), 70, 50, 50, 0, 10, 10, first.prices());
        active.add(next);
        budget.register(next);
        grants(next, plan(next, "second", "FOREGROUND", "SELECT"));
        assertEquals(
                "SESSION_BUDGET_EXHAUSTED",
                grants(next, plan(next, "third", "FOREGROUND", "SELECT"))
                        .get(0)
                        .path("deny_reason")
                        .asText());
        assertEquals(10, state(next).path("session_remaining_microusd").asLong());
    }

    @Test
    void configuredPolicyCannotBeReboundAndOldBootIsRejected() throws Exception {
        var c = context(100, 100, 50, 50, 10);
        var changed =
                new Context(
                        c.evidence(),
                        c.run(),
                        c.sessionScope(),
                        id(),
                        100,
                        100,
                        50,
                        50,
                        10,
                        10,
                        c.prices());
        assertThrows(Exception.class, () -> budget.register(changed));
        var reset =
                new Context(
                        c.evidence(), c.run(), id(), id(), 100, 100, 50, 50, 10, 10, c.prices());
        assertThrows(Exception.class, () -> budget.register(reset));
        var e = c.evidence();
        var boot =
                new EvidenceAuthority.Context(
                        e.scope(),
                        e.round(),
                        e.execution(),
                        "new-java",
                        "new-python",
                        e.catalogDataset(),
                        e.readLimit(),
                        e.candidates(),
                        false);
        var stale =
                new Context(
                        boot,
                        c.run(),
                        c.sessionScope(),
                        c.roundScope(),
                        100,
                        100,
                        50,
                        50,
                        10,
                        10,
                        c.prices());
        assertThrows(
                PersistenceRejected.class,
                () -> grants(stale, plan(c, "stale", "FOREGROUND", "SELECT")));
        assertEquals(100, state(c).path("round_remaining_microusd").asLong());
    }

    @Test
    void pythonClientRestartAndDoubleUseSameMessages() throws Exception {
        var c = context(1000, 1000, 500, 500, 10);
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/",
                exchange -> {
                    try {
                        if (!"Bearer be002-loopback"
                                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            exchange.sendResponseHeaders(401, -1);
                            return;
                        }
                        byte[] body = exchange.getRequestBody().readAllBytes();
                        var message =
                                body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
                        String path = exchange.getRequestURI().getPath(), response, type;
                        if (path.endsWith("invocation-grants")) {
                            response = budget.grants(c, message.path("payload").toString());
                            type = "GrantResults";
                        } else if (path.endsWith("budget-state")) {
                            response = budget.state(c);
                            type = "BudgetState";
                        } else {
                            response =
                                    budget.settle(
                                            c.evidence().execution(),
                                            message.path("payload").toString());
                            type = "UsageSettlement";
                        }
                        var out =
                                JSON.createObjectNode()
                                        .put("schema_version", "intel.v1")
                                        .put("contract_revision", "0.32")
                                        .put("request_id", message.path("request_id").asText(id()))
                                        .put("message_type", type);
                        out.putObject("trace_context")
                                .put("trace_id", "loopback")
                                .put("span_id", "loopback");
                        out.set("payload", JSON.readTree(response));
                        byte[] bytes = JSON.writeValueAsBytes(out);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception e) {
                        exchange.sendResponseHeaders(500, -1);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var intelligence =
                    java.nio.file.Path.of(System.getProperty("basedir"))
                            .resolveSibling("intelligence");
            String script =
                    """
                import asyncio,json,sys
                from datetime import datetime,timedelta,timezone
                from pathlib import Path
                from auralis_intelligence.agents.http import ControlHTTPClient
                from auralis_intelligence.agents.control_double import JavaControlDouble
                from auralis_intelligence.contracts import content_digest
                async def run():
                    base,execution,run_id,model,phase,fixture=sys.argv[1:]
                    p=json.load(sys.stdin)
                    client=ControlHTTPClient(base,'be002-loopback')
                    try:
                        actual=await client.request(execution,'invocation-grants',{'items':[p]})
                        assert actual['items'][0]['decision']==('GRANTED' if phase=='first' else 'ALREADY_CLAIMED'),actual
                        state=await client.request(execution,'budget-state',{})
                        assert state['round_remaining_microusd']==970,state
                        start=json.loads(Path(fixture).read_text(encoding='utf-8'))[0]['message']['payload']
                        start['execution_id']=execution;start['run_id']=run_id
                        start['deadlines']['round_deadline_at']=(datetime.now(timezone.utc)+timedelta(minutes=5)).isoformat().replace('+00:00','Z')
                        for binding in start['model_bindings'].values():
                            binding['ref']=model;binding['max_input_tokens']=10000;binding['max_output_tokens']=10000
                        limits=start['budget']['limits'];limits['session_cap_microusd']=1000;limits['round_cap_microusd']=1000;limits['lane_caps']={'FOREGROUND':500,'BACKGROUND':500};limits['model_calls']=10
                        start['payload_digest']=content_digest(start)
                        prices={model:{'input_microusd_per_million':1000000,'cached_input_microusd_per_million':500000,'output_microusd_per_million':2000000,'search_microusd_per_call':7}}
                        double=JavaControlDouble(start['boot']['coordinator_boot_id'],pricing_by_binding=prices)
                        double.register(start)
                        expected=await double.request(execution,'invocation-grants',{'items':[p]})
                        if phase=='first':assert actual==expected,(actual,expected)
                        else:
                            receipt={'invocation_id':p['invocation_id'],'receipt_id':execution+':receipt','outcome':'COMPLETED','finish':'COMPLETE','input_tokens':4,'cached_input_tokens':2,'output_tokens':3,'provider_request_ref':None}
                            actual=await client.request(execution,'usage-receipt',receipt)
                            expected=await double.request(execution,'usage-receipt',receipt)
                            assert actual['lanes']==expected['lanes'],(actual,expected)
                            assert actual==await client.request(execution,'usage-receipt',receipt)
                    finally:await client.close()
                asyncio.run(run())
                """;
            for (String phase : List.of("first", "restarted")) {
                if (phase.equals("restarted")) {
                    app.close();
                    boot();
                }
                var command =
                        new ProcessBuilder(
                                System.getenv("AURALIS_RECALL_PYTHON"),
                                "-c",
                                script,
                                "http://127.0.0.1:" + server.getAddress().getPort(),
                                c.evidence().execution(),
                                c.run(),
                                c.prices().keySet().iterator().next(),
                                phase,
                                intelligence
                                        .resolve("tests/fixtures/agent-contract-cases.json")
                                        .toString());
                command.environment().put("PYTHONPATH", intelligence.resolve("src").toString());
                command.redirectError(ProcessBuilder.Redirect.INHERIT);
                command.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                var child = command.start();
                try (var input = child.getOutputStream()) {
                    input.write(
                            plan(c, "python", "FOREGROUND", "SELECT")
                                    .toString()
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(child.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void allCapsAndAtomicBatchDecisions() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            var c =
                    context(
                            kind == 0 ? 50 : 1000,
                            kind < 2 ? 50 : 1000,
                            50,
                            kind < 2 ? 0 : 500,
                            10);
            var a = plan(c, "a", "FOREGROUND", "INTERPRET");
            var b = plan(c, "b", "FOREGROUND", "SELECT");
            var r = grants(c, b, a);
            assertEquals("GRANTED", r.get(1).path("decision").asText());
            assertEquals(
                    List.of("SESSION_BUDGET_EXHAUSTED", "ROUND_BUDGET_EXHAUSTED", "LANE_EXHAUSTED")
                            .get(kind),
                    r.get(0).path("deny_reason").asText());
            assertEquals(
                    30,
                    state(c).path("lanes").path("FOREGROUND").path("reserved_microusd").asLong());
            release();
        }
    }

    @Test
    void conflictRollsBackEntireBatchAndSameInvocationSendsOnce() throws Exception {
        var c = context(1000, 1000, 500, 500, 10);
        var a = plan(c, "a", "FOREGROUND", "SELECT");
        var z = plan(c, "z", "FOREGROUND", "SELECT");
        assertEquals("GRANTED", grants(c, z).get(0).path("decision").asText());
        z.put("max_output_tokens", 9);
        assertThrows(PersistenceRejected.class, () -> grants(c, a, z));
        assertEquals(
                30, state(c).path("lanes").path("FOREGROUND").path("reserved_microusd").asLong());
        assertEquals("GRANTED", grants(c, a).get(0).path("decision").asText());
        assertEquals("ALREADY_CLAIMED", grants(c, a).get(0).path("decision").asText());
    }

    @Test
    void concurrentReservationNeverOverspendsAndReplayHasOneWinner() throws Exception {
        var c = context(50, 50, 50, 0, 100);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<String>>();
            for (int n = 0; n < 6; n++) {
                var p = plan(c, "call" + n, "FOREGROUND", "SELECT");
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return grants(c, p).get(0).path("decision").asText();
                                }));
            }
            gate.countDown();
            int granted = 0;
            for (var f : futures) if (f.get(20, TimeUnit.SECONDS).equals("GRANTED")) granted++;
            assertEquals(1, granted);
            assertEquals(20, state(c).path("round_remaining_microusd").asLong());
        }
    }

    @Test
    void repairsAndCallCountsSurviveRegistrationAndRestart() throws Exception {
        var c = context(1000, 1000, 500, 500, 3);
        grants(
                c,
                plan(c, "fg", "FOREGROUND", "STRUCTURE_REPAIR"),
                plan(c, "bg", "BACKGROUND", "STRUCTURE_REPAIR"));
        app.close();
        boot();
        budget.register(c);
        assertEquals(
                "REPAIR_QUOTA_EXHAUSTED",
                grants(c, plan(c, "fg2", "FOREGROUND", "STRUCTURE_REPAIR"))
                        .get(0)
                        .path("deny_reason")
                        .asText());
        assertEquals(
                "REPAIR_QUOTA_EXHAUSTED",
                grants(c, plan(c, "bg2", "BACKGROUND", "STRUCTURE_REPAIR"))
                        .get(0)
                        .path("deny_reason")
                        .asText());
        grants(c, plan(c, "normal", "FOREGROUND", "SELECT"));
        assertEquals(
                "CALL_LIMIT",
                grants(c, plan(c, "normal2", "BACKGROUND", "WRITE_LONG"))
                        .get(0)
                        .path("deny_reason")
                        .asText());
        assertEquals(2, state(c).path("structure_repairs").asInt());
    }

    @Test
    void uncertainCannotDowngradeAndLateKnownUsageSettlesExactlyOnce() throws Exception {
        var c = context(1000, 1000, 500, 500, 10);
        var p = plan(c, "a", "FOREGROUND", "SELECT");
        grants(c, p);
        var unknown =
                receipt(p, "unknown", "UNCERTAIN")
                        .putNull("input_tokens")
                        .putNull("cached_input_tokens")
                        .putNull("output_tokens");
        assertEquals(
                "UNCERTAIN",
                JSON.readTree(budget.settle(c.evidence().execution(), unknown.toString()))
                        .path("status")
                        .asText());
        var no = receipt(p, "not-processed", "NOT_PROCESSED");
        assertEquals(
                "UNCERTAIN",
                JSON.readTree(budget.settle(c.evidence().execution(), no.toString()))
                        .path("status")
                        .asText());
        assertTrue(state(c).path("input_tokens").isNull());
        var done = receipt(p, "done", "COMPLETED");
        String once = budget.settle(c.evidence().execution(), done.toString());
        assertEquals(
                JSON.readTree(once),
                JSON.readTree(budget.settle(c.evidence().execution(), done.toString())));
        var lane = state(c).path("lanes").path("FOREGROUND");
        assertEquals(0, lane.path("reserved_microusd").asLong());
        assertEquals(9, lane.path("settled_microusd").asLong());
        done.put("output_tokens", 4);
        assertThrows(
                PersistenceRejected.class,
                () -> budget.settle(c.evidence().execution(), done.toString()));
        assertThrows(PersistenceRejected.class, () -> budget.settle("other", done.toString()));
    }

    @Test
    void knownUnsentReleasesButCountsStayConsumedAndOverrunStopsBinding() throws Exception {
        var c = context(1000, 1000, 500, 500, 10);
        var p = plan(c, "a", "FOREGROUND", "SELECT");
        grants(c, p);
        budget.settle(c.evidence().execution(), receipt(p, "none", "NOT_PROCESSED").toString());
        assertEquals(1000, state(c).path("round_remaining_microusd").asLong());
        assertEquals("ALREADY_CLAIMED", grants(c, p).get(0).path("decision").asText());
        var b = plan(c, "b", "FOREGROUND", "SELECT");
        grants(c, b);
        budget.settle(
                c.evidence().execution(),
                receipt(b, "large", "COMPLETED").put("output_tokens", 100).toString());
        assertEquals(
                203, state(c).path("lanes").path("FOREGROUND").path("settled_microusd").asLong());
        assertEquals(
                "EXECUTION_NOT_ACTIVE",
                grants(c, plan(c, "c", "FOREGROUND", "SELECT"))
                        .get(0)
                        .path("deny_reason")
                        .asText());
    }

    @Test
    void cancellationDeletionAndOldBootCannotCreateCallsButCanSettle() throws Exception {
        var c = context(1000, 1000, 500, 500, 10);
        var p = plan(c, "a", "FOREGROUND", "SELECT");
        grants(c, p);
        jdbc.update(
                "UPDATE discovery.execution SET cancellation_requested=true,revision=revision+1 WHERE id=?",
                c.evidence().execution());
        assertThrows(
                PersistenceRejected.class, () -> grants(c, plan(c, "b", "FOREGROUND", "SELECT")));
        release();
        var s = c.evidence().scope();
        tx.write(() -> sessions.close(s, "DELETED"));
        var closed =
                new SessionStore.Scope(
                        s.subjectId(),
                        s.sessionId(),
                        s.subjectEpoch(),
                        s.sessionEpoch() + 1,
                        s.revision() + 1);
        assertTrue(tx.write(() -> sessions.purgeSession(closed)));
        assertEquals(
                "SETTLED",
                JSON.readTree(
                                budget.settle(
                                        c.evidence().execution(),
                                        receipt(p, "late", "COMPLETED").toString()))
                        .path("status")
                        .asText());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.session WHERE id=?",
                        Integer.class,
                        s.sessionId()));
        assertNull(
                jdbc.queryForMap(
                                "SELECT session_id,execution_id FROM discovery.invocation WHERE id=?",
                                p.path("invocation_id").asText())
                        .get("session_id"));
        long revision =
                jdbc.queryForObject(
                        "SELECT revision FROM discovery.subject WHERE id=?",
                        Long.class,
                        s.subjectId());
        assertTrue(
                tx.write(() -> sessions.revokeSubject(s.subjectId(), revision, s.subjectEpoch())));
        assertTrue(tx.write(() -> sessions.purgeSubject(s.subjectId(), s.subjectEpoch() + 1)));
        var retained =
                jdbc.queryForMap(
                        "SELECT session_id,round_id,execution_id FROM discovery.invocation WHERE id=?",
                        p.path("invocation_id").asText());
        assertTrue(retained.values().stream().allMatch(Objects::isNull));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.usage_receipt WHERE invocation_id=?",
                        Integer.class,
                        p.path("invocation_id").asText()));
    }

    @Test
    void unknownReadAndWrongCandidateDeniedBeforeReservation() throws Exception {
        var c = context(1000, 1000, 500, 500, 10);
        var p = plan(c, "a", "FOREGROUND", "SELECT");
        p.withArray("read_ids").add("missing");
        assertEquals("READ_UNKNOWN", grants(c, p).get(0).path("deny_reason").asText());
        p.put("candidate_id", "foreign");
        assertThrows(PersistenceRejected.class, () -> grants(c, p));
        assertEquals(1000, state(c).path("round_remaining_microusd").asLong());
    }
}
