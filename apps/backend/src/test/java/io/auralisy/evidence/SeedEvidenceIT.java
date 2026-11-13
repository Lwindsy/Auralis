package io.auralisy.evidence;

import static io.auralisy.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralisy.bootstrap.OnlineApplication;
import io.auralisy.evidence.api.*;
import io.auralisy.session.api.*;
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
class SeedEvidenceIT {
    static final String G = "00000000-0000-0000-0000-000000000101",
            A = "00000000-0000-0000-0000-000000000201";
    JdbcTemplate admin, jdbc;
    ConfigurableApplicationContext app;
    SubjectSession subjects;
    EvidenceAuthority authority;
    SessionStore sessions;
    long dataset;
    List<Long> previous;
    io.auralisy.discovery.api.DiscoveryStore discovery;
    io.auralisy.persistence.ShortTransactions tx;
    List<EvidenceAuthority.Context> active = new ArrayList<>();

    @BeforeAll
    void setup() {
        String url = System.getenv("AURALIS_TEST_POSTGRES_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        var source =
                new DriverManagerDataSource(
                        url, "postgres", System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        admin = new JdbcTemplate(source);
        previous =
                admin.queryForList("SELECT dataset_id FROM ingestion.active_dataset", Long.class);
        dataset =
                admin.queryForObject(
                        "INSERT INTO ingestion.dataset(manifest_sha256,layout_sha256,purpose,manifest,status) VALUES(repeat('f',64),repeat('f',64),'private-development','{}','IMPORTING') RETURNING id",
                        Long.class);
        admin.update("INSERT INTO catalog.genre VALUES(?,1,?::uuid,'爵士','')", dataset, G);
        admin.update(
                "INSERT INTO catalog.artist VALUES(?,1,?::uuid,'艺人','Artist','',NULL,now())",
                dataset,
                A);
        admin.update(
                "INSERT INTO catalog.album(dataset_id,id,mbid,title,comment,source_credit_id,primary_type,source_updated) VALUES(?,1,?::uuid,'专辑','',1,'Album',now())",
                dataset,
                A);
        admin.update("INSERT INTO catalog.album_credit VALUES(?,1,0,1,'艺人','')", dataset);
        admin.update("INSERT INTO catalog.album_genre VALUES(?,1,1,1,1,now())", dataset);
        admin.update(
                "INSERT INTO catalog.album(dataset_id,id,mbid,title,comment,source_credit_id,primary_type,source_updated) VALUES(?,2,'00000000-0000-0000-0000-000000000202'::uuid,'新增专辑','',1,'Album',now())",
                dataset);
        admin.update("INSERT INTO catalog.album_credit VALUES(?,2,0,1,'艺人','')", dataset);
        admin.update("INSERT INTO catalog.album_genre VALUES(?,2,1,1,1,now())", dataset);
        admin.update("UPDATE ingestion.dataset SET status='ACTIVE' WHERE id=?", dataset);
        admin.update(
                "INSERT INTO ingestion.active_dataset VALUES(true,?) ON CONFLICT(singleton) DO UPDATE SET dataset_id=EXCLUDED.dataset_id",
                dataset);
        admin.execute("CREATE ROLE be009_online LOGIN PASSWORD 'be009-synthetic-password'");
        admin.execute("GRANT USAGE ON SCHEMA catalog,ingestion,discovery TO be009_online");
        admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA catalog,ingestion TO be009_online");
        admin.execute(
                "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO be009_online");
        admin.execute("GRANT SELECT ON public.flyway_schema_history TO be009_online");
        var application = new SpringApplication(OnlineApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        var properties = new HashMap<String, Object>();
        properties.put("auralis.jdbc-url", url);
        properties.put("auralis.database-user", "be009_online");
        properties.put("auralis.database-password", "be009-synthetic-password");
        properties.put("auralis.product-origin", "http://127.0.0.1:8080");
        properties.put("auralis.python-url", "http://127.0.0.1:1");
        properties.put("auralis.service-token", "be009-service-synthetic-token-123456789");
        properties.put("auralis.contract-revision", "0.32");
        properties.put(
                "auralis.subject-secret",
                "be009-test-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
        properties.put("auralis.allow-insecure-cookie", true);
        properties.put("auralis.session-cleanup-enabled", false);
        properties.put(
                "AURALIS_GENRE_TAXONOMY",
                io.auralisy.catalog.TaxonomyFixtures.write(admin).toString());
        application.setDefaultProperties(properties);
        app = application.run();
        subjects = app.getBean(SubjectSession.class);
        authority = app.getBean(EvidenceAuthority.class);
        discovery = app.getBean(io.auralisy.discovery.api.DiscoveryStore.class);
        tx = app.getBean(io.auralisy.persistence.ShortTransactions.class);
        sessions = app.getBean(SessionStore.class);
        jdbc = app.getBean(JdbcTemplate.class);
    }

    @AfterAll
    void close() {
        if (app != null) app.close();
        if (previous.isEmpty())
            admin.update("DELETE FROM ingestion.active_dataset WHERE dataset_id=?", dataset);
        else admin.update("UPDATE ingestion.active_dataset SET dataset_id=?", previous.getFirst());
    }

    record Input(
            SubjectSession.Bootstrap browser,
            SessionStore.Scope scope,
            EvidenceAuthority.Context context) {}

    ObjectNode raw() {
        var raw =
                JSON.createObjectNode()
                        .put("mode", "explore")
                        .put("language", "zh")
                        .put("request_text", "");
        raw.putArray("liked_genres");
        raw.putArray("disliked_genre_ids");
        raw.putArray("liked_albums")
                .addObject()
                .put("release_group_id", A)
                .putNull("external")
                .put("strength", "normal")
                .put("focused", false);
        raw.put("input_digest", contentDigest(raw, "input_digest"));
        return raw;
    }

    Input input() {
        var browser = subjects.bootstrap(null);
        return replace(browser, null, 0);
    }

    Input replace(SubjectSession.Bootstrap browser, String expected, long revision) {
        return replace(browser, expected, revision, raw());
    }

    Input replace(
            SubjectSession.Bootstrap browser, String expected, long revision, ObjectNode raw) {
        raw.put("input_digest", contentDigest(raw, "input_digest"));
        var editable = raw.deepCopy();
        editable.remove("input_digest");
        var albums = editable.putArray("liked_albums");
        for (var album : raw.path("liked_albums"))
            albums.addObject()
                    .put("mb_release_group_id", album.path("release_group_id").asText())
                    .put("strength", album.path("strength").asText())
                    .put("focused", album.path("focused").asBoolean());
        var next =
                subjects.replace(
                        browser.credential(),
                        browser.csrf(),
                        UUID.randomUUID().toString(),
                        expected,
                        revision,
                        editable.toString(),
                        raw.toString());
        return new Input(browser, next.scope(), execution(next.scope(), 100));
    }

    EvidenceAuthority.Context execution(SessionStore.Scope scope, int limit) {
        String round = UUID.randomUUID().toString(),
                run = UUID.randomUUID().toString(),
                execution = UUID.randomUUID().toString();
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    discovery.createRound(
                            scope, round, 1, "{}", "{}", Instant.now().plusSeconds(120));
                    discovery.createIntent(
                            scope,
                            round,
                            run,
                            "test",
                            1,
                            digest(JSON.getNodeFactory().textNode(run)),
                            Instant.now(),
                            Instant.now().plusSeconds(30));
                    int slot =
                            jdbc.queryForObject(
                                    "SELECT min(slot_no) FROM discovery.execution_slot WHERE state='FREE'",
                                    Integer.class);
                    discovery.reserveExecution(
                            scope, round, run, execution, slot, "java", "python");
                    return null;
                });
        var context =
                new EvidenceAuthority.Context(
                        scope,
                        round,
                        execution,
                        "java",
                        "python",
                        dataset,
                        limit,
                        Map.of(A, A),
                        false);
        active.add(context);
        return context;
    }

    @AfterEach
    void release() {
        for (var c : active)
            tx.write(
                    () ->
                            discovery.releaseExecution(
                                    c.scope(), c.execution(), "java", "python", Instant.now()));
        active.clear();
    }

    ObjectNode request(String read, String url) {
        return JSON.createObjectNode()
                .put("read_id", read)
                .put("candidate_id", A)
                .put("url", url)
                .put("purpose", "SCREEN_RESEARCH");
    }

    ObjectNode record(EvidenceAuthority.Context c, String read, String url, String text)
            throws Exception {
        assertEquals(
                "GRANTED",
                JSON.readTree(authority.authorize(c, request(read, url).toString()))
                        .path("decision")
                        .asText());
        var receipt =
                JSON.createObjectNode()
                        .put("read_id", read)
                        .put("candidate_id", A)
                        .put("purpose", "SCREEN_RESEARCH")
                        .put("requested_url", url)
                        .put("final_url", url)
                        .put("publisher_key", "example.com")
                        .put("status", "READ_OK")
                        .putNull("failure_reason")
                        .put("read_at", Instant.now().toString())
                        .put("content_digest", digest(JSON.getNodeFactory().textNode("whole-body")))
                        .put("text_chars", text.length())
                        .putNull("near_duplicate_of")
                        .put("retrieval_mode", "NETWORK")
                        .put("excerpt_digest", digest(JSON.getNodeFactory().textNode(text)));
        receipt.putObject("checks").put("mentions_title", true).put("mentions_artist", true);
        var payload = JSON.createObjectNode();
        payload.putArray("items").add(receipt);
        assertEquals(
                read,
                JSON.readTree(authority.receipts(c, payload.toString()))
                        .path("accepted_read_ids")
                        .get(0)
                        .asText());
        return receipt;
    }

    ObjectNode update(EvidenceAuthority.Context c, JsonNode receipt, String text) throws Exception {
        var identity = JSON.readTree(authority.seeds(c)).path("items").get(0).path("identity");
        var payload = JSON.createObjectNode();
        var row =
                payload.putArray("items")
                        .addObject()
                        .put("seed_id", A)
                        .put("status", receipt == null ? "NO_USABLE_PAGE" : "READ_AVAILABLE");
        row.set("identity", identity);
        var sources = row.putArray("sources");
        if (receipt != null)
            sources.addObject()
                    .put("read_id", receipt.path("read_id").asText())
                    .put("url", receipt.path("final_url").asText())
                    .put("read_at", receipt.path("read_at").asText())
                    .put("text", text)
                    .put("text_digest", digest(JSON.getNodeFactory().textNode(text)));
        return payload;
    }

    @Test
    void budgetConsumesActualSeedOwnershipAndRejectsForeignReads() throws Exception {
        var first = input();
        var c = first.context();
        String read = UUID.randomUUID().toString();
        var receipt = record(c, read, "https://example.com/budget-seed", "真实种子摘录");
        authority.saveSeeds(c, update(c, receipt, "真实种子摘录").toString());
        var other = input();
        var foreign =
                record(
                        other.context(),
                        UUID.randomUUID().toString(),
                        "https://example.com/foreign",
                        "其他会话");
        var budget = app.getBean(io.auralisy.budget.api.BudgetAuthority.class);
        String run =
                jdbc.queryForObject(
                        "SELECT run_id FROM discovery.execution WHERE id=?",
                        String.class,
                        c.execution());
        var context =
                new io.auralisy.budget.api.BudgetAuthority.Context(
                        c,
                        run,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        1000,
                        1000,
                        500,
                        500,
                        10,
                        10,
                        Map.of(
                                "test-model",
                                new io.auralisy.budget.api.BudgetAuthority.Price(
                                        "test", 1000000, 1000000, 1000000, 0, 100, 100)));
        budget.register(context);
        var request = JSON.createObjectNode();
        var plan =
                request.putArray("items")
                        .addObject()
                        .put("invocation_id", c.execution() + ":seed-budget")
                        .put("attempt", 1)
                        .put("purpose", "SELECT")
                        .put("cost_class", "MODEL")
                        .put("lane", "FOREGROUND")
                        .put("run_id", run)
                        .putNull("candidate_id")
                        .put("model_binding_ref", "test-model")
                        .put("max_input_tokens", 10)
                        .put("max_output_tokens", 10)
                        .put("input_digest", "sha256:" + "e".repeat(64));
        plan.putArray("read_ids").add(read);
        assertEquals(
                "GRANTED",
                JSON.readTree(budget.grants(context, request.toString()))
                        .path("items")
                        .get(0)
                        .path("decision")
                        .asText());
        plan.put("invocation_id", c.execution() + ":foreign-budget");
        plan.putArray("read_ids").add(foreign.path("read_id").asText());
        assertEquals(
                "READ_UNKNOWN",
                JSON.readTree(budget.grants(context, request.toString()))
                        .path("items")
                        .get(0)
                        .path("deny_reason")
                        .asText());
    }

    @Test
    void readClaimsRedirectsAndRoundLimitDoNotDoubleSend() throws Exception {
        var i = input();
        var c = i.context();
        String read = UUID.randomUUID().toString();
        var req = request(read, "https://example.com/a");
        assertEquals(
                "GRANTED",
                JSON.readTree(authority.authorize(c, req.toString())).path("decision").asText());
        assertEquals(
                "ALREADY_CLAIMED",
                JSON.readTree(authority.authorize(c, req.toString())).path("decision").asText());
        var limited =
                new EvidenceAuthority.Context(
                        c.scope(),
                        c.round(),
                        c.execution(),
                        "java",
                        "python",
                        dataset,
                        1,
                        Map.of(A, A),
                        false);
        assertEquals(
                "DENIED",
                JSON.readTree(
                                authority.authorize(
                                        limited,
                                        request("second", "https://example.com/b").toString()))
                        .path("decision")
                        .asText());
        req.put("candidate_id", "unknown");
        assertThrows(Exception.class, () -> authority.authorize(c, req.toString()));
        assertFalse(authority.usableRead(c, read, A, false));
    }

    @Test
    void seedReplayTamperingAndNegativeState() throws Exception {
        var i = input();
        var c = i.context();
        String text = "艺人 专辑 guitar rhythm";
        var receipt = record(c, UUID.randomUUID().toString(), "https://example.com/read", text);
        var update = update(c, receipt, text);
        var state = JSON.readTree(authority.saveSeeds(c, update.toString()));
        assertEquals(state, JSON.readTree(authority.saveSeeds(c, update.toString())));
        assertTrue(authority.usableRead(c, receipt.path("read_id").asText(), A, true));
        var changed = update.deepCopy();
        var source = (ObjectNode) changed.path("items").get(0).path("sources").get(0);
        source.put("text", "forged")
                .put("text_digest", digest(JSON.getNodeFactory().textNode("forged")));
        assertThrows(Exception.class, () -> authority.saveSeeds(c, changed.toString()));
        source.set("text", update.path("items").get(0).path("sources").get(0).path("text"));
        source.set(
                "text_digest",
                update.path("items").get(0).path("sources").get(0).path("text_digest"));
        source.put("url", "https://example.com/forged");
        assertThrows(Exception.class, () -> authority.saveSeeds(c, changed.toString()));
        var other = input();
        var empty = update(other.context(), null, "");
        assertEquals(
                "NO_USABLE_PAGE",
                JSON.readTree(authority.saveSeeds(other.context(), empty.toString()))
                        .path("items")
                        .get(0)
                        .path("evidence")
                        .path("status")
                        .asText());
    }

    @Test
    void derivationRebindsReadsAndSurvivesSourcePurge() throws Exception {
        var i = input();
        String text = "艺人 专辑 piano";
        var receipt =
                record(
                        i.context(),
                        UUID.randomUUID().toString(),
                        "https://example.com/source",
                        text);
        authority.saveSeeds(i.context(), update(i.context(), receipt, text).toString());
        tx.write(
                () ->
                        discovery.releaseExecution(
                                i.scope(),
                                i.context().execution(),
                                "java",
                                "python",
                                Instant.now()));
        var next = replace(i.browser(), i.scope().sessionId(), i.scope().revision());
        var state = JSON.readTree(authority.seeds(next.context()));
        var copied = state.path("items").get(0).path("evidence").path("sources").get(0);
        String id = copied.path("read_id").asText();
        assertNotEquals(receipt.path("read_id").asText(), id);
        assertEquals(receipt.path("read_at"), copied.path("read_at"));
        assertFalse(
                authority.usableRead(next.context(), receipt.path("read_id").asText(), A, true));
        assertTrue(authority.usableRead(next.context(), id, A, true));
        assertFalse(authority.usableRead(next.context(), id, A, false));
        var sourceScope =
                new SessionStore.Scope(
                        i.scope().subjectId(),
                        i.scope().sessionId(),
                        i.scope().subjectEpoch(),
                        i.scope().sessionEpoch() + 1,
                        i.scope().revision() + 1);
        tx.write(() -> sessions.close(sourceScope, "DELETED"));
        subjects.cleanup(100);
        assertEquals(state, JSON.readTree(authority.seeds(next.context())));
        admin.update(
                "UPDATE discovery.seed_evidence SET expires_at=read_at+interval '1 second' WHERE session_id=?",
                next.scope().sessionId());
        assertEquals(state, JSON.readTree(authority.seeds(next.context())));
    }

    @Test
    void deletionAndCrossSubjectLateWritesReject() throws Exception {
        var a = input();
        var b = input();
        var receipt =
                record(a.context(), UUID.randomUUID().toString(), "https://example.com/a", "music");
        var update = update(a.context(), receipt, "music");
        assertThrows(Exception.class, () -> authority.saveSeeds(b.context(), update.toString()));
        subjects.erase(a.browser().credential(), a.browser().csrf());
        assertThrows(Exception.class, () -> authority.saveSeeds(a.context(), update.toString()));
        assertThrows(Exception.class, () -> authority.seeds(a.context()));
    }

    @Test
    void actualPythonClientAndSeedContextConsumeJavaOverLoopback() throws Exception {
        var input = input();
        var c = input.context();
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.createContext(
                "/internal/v1/executions/" + c.execution() + "/",
                exchange -> {
                    try {
                        if (!"Bearer be009-loopback"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("Authorization"))
                                || !"coordinator"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("X-Auralis-Audience"))) {
                            exchange.sendResponseHeaders(401, -1);
                            return;
                        }
                        var body = exchange.getRequestBody().readAllBytes();
                        var message =
                                body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
                        var payload = message.path("payload");
                        String path = exchange.getRequestURI().getPath();
                        String response, type;
                        if (path.endsWith("read-authorization")) {
                            response = authority.authorize(c, payload.toString());
                            type = "ReadAuthorizationResult";
                        } else if (path.endsWith("read-receipts")) {
                            response = authority.receipts(c, payload.toString());
                            type = "ReadRegistrationResult";
                        } else {
                            response =
                                    exchange.getRequestMethod().equals("PUT")
                                            ? authority.saveSeeds(c, payload.toString())
                                            : authority.seeds(c);
                            type = "SeedEvidenceState";
                        }
                        var out =
                                JSON.createObjectNode()
                                        .put("schema_version", "intel.v1")
                                        .put(
                                                "contract_revision",
                                                app.getBean(
                                                                io.auralisy.bootstrap.WireProtocol
                                                                        .class)
                                                        .revision())
                                        .put(
                                                "request_id",
                                                message.path("request_id")
                                                        .asText(UUID.randomUUID().toString()))
                                        .put("message_type", type);
                        out.putObject("trace_context")
                                .put("trace_id", "loopback")
                                .put("span_id", "loopback");
                        out.set("payload", JSON.readTree(response));
                        byte[] bytes = JSON.writeValueAsBytes(out);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception error) {
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
            String python = System.getenv("AURALIS_RECALL_PYTHON");
            assertNotNull(python);
            var command =
                    new ProcessBuilder(
                            python,
                            intelligence.resolve("tests/seed_java_wire.py").toString(),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            c.execution(),
                            A);
            command.environment().put("PYTHONPATH", intelligence.resolve("src").toString());
            command.inheritIO();
            var child = command.start();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentAuthorizationConsumesTheRoundLimitOnce() throws Exception {
        var i = input();
        var original = i.context();
        var c =
                new EvidenceAuthority.Context(
                        i.scope(),
                        original.round(),
                        original.execution(),
                        "java",
                        "python",
                        dataset,
                        1,
                        Map.of(A, A),
                        false);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int n = 0; n < 2; n++) {
                String id = UUID.randomUUID().toString();
                tasks.add(
                        () -> {
                            barrier.await();
                            return JSON.readTree(
                                            authority.authorize(
                                                    c,
                                                    request(id, "https://example.com/concurrent")
                                                            .toString()))
                                    .path("decision")
                                    .asText();
                        });
            }
            var results = pool.invokeAll(tasks);
            var decisions = new ArrayList<String>();
            for (var result : results) decisions.add(result.get());
            assertEquals(1, Collections.frequency(decisions, "GRANTED"));
            assertEquals(1, Collections.frequency(decisions, "DENIED"));
        }
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.read_claim WHERE round_id=?",
                        Integer.class,
                        c.round()));
    }

    @Test
    void savingAndDeletingConcurrentlyCannotLeaveAccessibleEvidence() throws Exception {
        var i = input();
        String text = "piano guitar";
        var receipt =
                record(i.context(), UUID.randomUUID().toString(), "https://example.com/race", text);
        var update = update(i.context(), receipt, text);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var save =
                    pool.submit(
                            () -> {
                                barrier.await();
                                try {
                                    authority.saveSeeds(i.context(), update.toString());
                                } catch (io.auralisy.persistence.PersistenceRejected expected) {
                                }
                                return true;
                            });
            var erase =
                    pool.submit(
                            () -> {
                                barrier.await();
                                subjects.erase(i.browser().credential(), i.browser().csrf());
                                return true;
                            });
            assertTrue(save.get(10, TimeUnit.SECONDS));
            assertTrue(erase.get(10, TimeUnit.SECONDS));
        }
        assertThrows(Exception.class, () -> authority.seeds(i.context()));
        tx.write(
                () ->
                        discovery.releaseExecution(
                                i.scope(),
                                i.context().execution(),
                                "java",
                                "python",
                                Instant.now()));
        subjects.cleanup(100);
        subjects.cleanup(100);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.seed_evidence WHERE session_id=?",
                        Integer.class,
                        i.scope().sessionId()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.seed_read_binding WHERE session_id=?",
                        Integer.class,
                        i.scope().sessionId()));
    }

    @Test
    void failedDerivationRollsBackTheWholeSessionReplacement() throws Exception {
        var i = input();
        String text = "piano";
        var receipt =
                record(
                        i.context(),
                        UUID.randomUUID().toString(),
                        "https://example.com/rollback",
                        text);
        authority.saveSeeds(i.context(), update(i.context(), receipt, text).toString());
        admin.execute(
                "CREATE FUNCTION discovery.be009_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.source_read_id IS NOT NULL THEN RAISE EXCEPTION 'SYNTHETIC_COPY_FAILURE'; END IF;RETURN NEW;END $$");
        admin.execute(
                "CREATE TRIGGER be009_fail BEFORE INSERT ON discovery.seed_read_binding FOR EACH ROW EXECUTE FUNCTION discovery.be009_fail()");
        try {
            assertThrows(
                    Exception.class,
                    () -> replace(i.browser(), i.scope().sessionId(), i.scope().revision()));
            assertEquals(i.scope(), sessions.current(i.scope().subjectId()).orElseThrow().scope());
            assertTrue(
                    authority.usableRead(i.context(), receipt.path("read_id").asText(), A, true));
        } finally {
            admin.execute("DROP TRIGGER be009_fail ON discovery.seed_read_binding");
            admin.execute("DROP FUNCTION discovery.be009_fail()");
        }
    }

    @Test
    void unchangedSeedIsCopiedAndOnlyNewSeedRemainsPending() throws Exception {
        var i = input();
        String text = "piano";
        var receipt =
                record(
                        i.context(),
                        UUID.randomUUID().toString(),
                        "https://example.com/unchanged",
                        text);
        authority.saveSeeds(i.context(), update(i.context(), receipt, text).toString());
        String added = "00000000-0000-0000-0000-000000000202";
        var nextRaw = raw();
        nextRaw.withArray("liked_albums")
                .addObject()
                .put("release_group_id", added)
                .putNull("external")
                .put("strength", "normal")
                .put("focused", false);
        tx.write(
                () ->
                        discovery.releaseExecution(
                                i.scope(),
                                i.context().execution(),
                                "java",
                                "python",
                                Instant.now()));
        var next = replace(i.browser(), i.scope().sessionId(), i.scope().revision(), nextRaw);
        var state = JSON.readTree(authority.seeds(next.context()));
        assertEquals(2, state.path("items").size());
        assertEquals(
                "READ_AVAILABLE",
                state.path("items").get(0).path("evidence").path("status").asText());
        assertTrue(state.path("items").get(1).path("evidence").isNull());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.seed_evidence WHERE session_id=?",
                        Integer.class,
                        next.scope().sessionId()));
    }
}
