package io.auralis.publication;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.bootstrap.OnlineApplication;
import io.auralis.budget.api.*;
import io.auralis.catalog.api.*;
import io.auralis.discovery.api.*;
import io.auralis.evidence.api.*;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.preference.api.*;
import io.auralis.publication.api.*;
import io.auralis.session.api.*;
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
class PublicationDisplayIT {
    static final String G = "00000000-0000-0000-0000-000000000101",
            A = "00000000-0000-0000-0000-000000000201";
    static final String MANIFEST = UUID.randomUUID().toString().replace("-", "").repeat(2);
    JdbcTemplate admin, jdbc;
    ConfigurableApplicationContext app;
    SubjectSession subjects;
    EvidenceAuthority authority;
    SessionStore sessions;
    long dataset;
    List<Long> previous;
    io.auralis.discovery.api.DiscoveryStore discovery;
    io.auralis.persistence.ShortTransactions tx;
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
        admin.execute(
                "CREATE TABLE IF NOT EXISTS public.bootstrap_probe(id bigint primary key,value varchar(255))");
        previous =
                admin.queryForList("SELECT dataset_id FROM ingestion.active_dataset", Long.class);
        dataset =
                admin.queryForObject(
                        "INSERT INTO ingestion.dataset(manifest_sha256,layout_sha256,purpose,manifest,status) VALUES(?,?,'private-development','{}','IMPORTING') RETURNING id",
                        Long.class,
                        MANIFEST,
                        MANIFEST);
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
        for (int n = 3; n <= 4; n++) {
            admin.update(
                    "INSERT INTO catalog.album(dataset_id,id,mbid,title,comment,source_credit_id,primary_type,source_updated) VALUES(?,?,?::uuid,?,'',1,'Album',now())",
                    dataset,
                    n,
                    album(n),
                    "专辑" + n);
            admin.update("INSERT INTO catalog.album_credit VALUES(?,?,0,1,'艺人','')", dataset, n);
            admin.update("INSERT INTO catalog.album_genre VALUES(?,?,1,1,1,now())", dataset, n);
        }
        admin.update("UPDATE ingestion.dataset SET status='ACTIVE' WHERE id=?", dataset);
        admin.update(
                "INSERT INTO ingestion.active_dataset VALUES(true,?) ON CONFLICT(singleton) DO UPDATE SET dataset_id=EXCLUDED.dataset_id",
                dataset);
        admin.execute("CREATE ROLE be011_online LOGIN PASSWORD 'be011-synthetic-password'");
        admin.execute("GRANT USAGE ON SCHEMA catalog,ingestion,discovery TO be011_online");
        admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA catalog,ingestion TO be011_online");
        admin.execute(
                "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO be011_online");
        admin.execute("GRANT SELECT ON public.flyway_schema_history TO be011_online");
        admin.execute("GRANT SELECT,INSERT,DELETE ON public.bootstrap_probe TO be011_online");
        var application = new SpringApplication(OnlineApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        var properties = new HashMap<String, Object>();
        properties.put("auralis.jdbc-url", url);
        properties.put("auralis.database-user", "be011_online");
        properties.put("auralis.database-password", "be011-synthetic-password");
        properties.put("auralis.product-origin", "http://127.0.0.1:8080");
        properties.put("auralis.python-url", "http://127.0.0.1:1");
        properties.put("auralis.service-token", "be011-service-synthetic-token-123456789");
        properties.put("auralis.contract-revision", "0.30");
        properties.put(
                "auralis.subject-secret",
                "be011-test-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
        properties.put("auralis.allow-insecure-cookie", true);
        properties.put("auralis.session-cleanup-enabled", false);
        application.setDefaultProperties(properties);
        app = application.run();
        subjects = app.getBean(SubjectSession.class);
        authority = app.getBean(EvidenceAuthority.class);
        discovery = app.getBean(io.auralis.discovery.api.DiscoveryStore.class);
        tx = app.getBean(io.auralis.persistence.ShortTransactions.class);
        sessions = app.getBean(SessionStore.class);
        jdbc = app.getBean(JdbcTemplate.class);
        setupPublication();
    }

    @AfterAll
    void close() {
        if (app != null) app.close();
        if (snapshot != null)
            admin.update(
                    "UPDATE discovery.audience_snapshot SET active=false WHERE id=?", snapshot);
        if (previousAudience != null)
            for (String old : previousAudience)
                admin.update("UPDATE discovery.audience_snapshot SET active=true WHERE id=?", old);
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

    PublicationAuthority publication;
    BudgetAuthority budget;
    AudienceQuery.Binding population;
    String snapshot;
    List<String> previousAudience;

    static String album(int n) {
        return String.format("00000000-0000-0000-0000-%012d", 200 + n);
    }

    void setupPublication() {
        publication = app.getBean(PublicationAuthority.class);
        budget = app.getBean(BudgetAuthority.class);
        snapshot = "sha256:" + UUID.randomUUID().toString().replace("-", "").repeat(2);
        previousAudience =
                admin.queryForList(
                        "SELECT id FROM discovery.audience_snapshot WHERE active", String.class);
        population = new AudienceQuery.Binding("test-population", "0", 100, snapshot);
        tx.write(
                () -> {
                    var store = app.getBean(AudienceStore.class);
                    store.stage(
                            new AudienceStore.Snapshot(
                                    snapshot,
                                    population.dataset(),
                                    "0",
                                    100,
                                    snapshot,
                                    MANIFEST,
                                    "{\"scope\":\"FULL_CATALOG\",\"catalog_dataset_id\":"
                                            + dataset
                                            + "}"));
                    var counts = new HashMap<UUID, Long>();
                    for (int n = 1; n <= 4; n++) counts.put(UUID.fromString(album(n)), 100L);
                    store.putCounts(snapshot, "0", 100, counts);
                    return null;
                });
        admin.update("UPDATE discovery.audience_snapshot SET active=false WHERE active");
        admin.update("UPDATE discovery.audience_snapshot SET active=true WHERE id=?", snapshot);
    }

    record Flow(
            PublicationAuthority.Context context,
            ObjectNode bundle,
            Map<String, String> reads,
            String invocation) {}

    SessionStore.Scope latest(Flow f) {
        return sessions.refresh(f.context().execution().evidence().scope());
    }

    Flow flow(int target) throws Exception {
        var input = input();
        var old = input.context();
        String run =
                jdbc.queryForObject(
                        "SELECT run_id FROM discovery.execution WHERE id=?",
                        String.class,
                        old.execution());
        publication.begin(old.scope(), old.round(), run, target, "RUNNING");
        var s = sessions.refresh(old.scope());
        tx.write(() -> discovery.transitionRun(s, run, 0, "WAITING", "DISPATCHING"));
        var candidates = new LinkedHashMap<String, String>();
        for (int n = 1; n <= 4; n++) candidates.put(album(n), album(n));
        var e =
                new EvidenceAuthority.Context(
                        s,
                        old.round(),
                        old.execution(),
                        "java",
                        "python",
                        dataset,
                        100,
                        candidates,
                        false);
        var b =
                new BudgetAuthority.Context(
                        e,
                        run,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        1000000,
                        1000000,
                        500000,
                        500000,
                        100,
                        100,
                        Map.of(
                                "test-model",
                                new BudgetAuthority.Price(
                                        "test", 1000000, 1000000, 1000000, 0, 1000, 1000)));
        budget.register(b);
        var base =
                JSON.readTree(
                                java.nio.file.Path.of(System.getProperty("basedir"))
                                        .resolveSibling(
                                                "intelligence/tests/fixtures/agent-contract-cases.json")
                                        .toFile())
                        .get(0)
                        .path("message")
                        .path("payload");
        var policy = (ObjectNode) base.path("agent_policy").deepCopy();
        ((ObjectNode) policy.path("writing")).put("short_min_han", 1).put("short_max_han", 1000);
        policy.put("digest", contentDigest(policy, "digest"));
        String resolution = UUID.randomUUID().toString();
        var pref =
                JSON.createObjectNode()
                        .put("resolution_id", resolution)
                        .put("input_digest", "sha256:" + "1".repeat(64))
                        .put("reference_revision", "test")
                        .put("parser_revision", "test")
                        .put("mode", "explore")
                        .put("has_positive_signal", true);
        pref.putArray("seeds")
                .addObject()
                .put("id", A)
                .put("strength", "normal")
                .put("focused", false);
        for (String name :
                List.of(
                        "tags",
                        "disliked_tags",
                        "forbidden_tags",
                        "required_tags",
                        "soft_preferences",
                        "overrides")) pref.putArray(name);
        pref.put("effective_preference_digest", contentDigest(pref, "effective_preference_digest"));
        tx.write(
                () -> {
                    app.getBean(PreferenceStore.class)
                            .save(
                                    s,
                                    resolution,
                                    pref.path("input_digest").asText(),
                                    "test",
                                    "test",
                                    pref.path("effective_preference_digest").asText(),
                                    pref.toString());
                    return null;
                });
        var bundle =
                JSON.createObjectNode().put("candidate_bundle_id", UUID.randomUUID().toString());
        var accepted = bundle.putArray("accepted");
        bundle.putArray("rejected");
        for (var entry :
                app
                        .getBean(CatalogQuery.class)
                        .albums(
                                dataset,
                                candidates.keySet().stream().map(UUID::fromString).toList())
                        .values()
                        .stream()
                        .sorted(Comparator.comparing(a -> a.id().toString()))
                        .toList()) {
            var a =
                    accepted.addObject()
                            .put("release_group_id", entry.id().toString())
                            .put("recall_rank", accepted.size());
            var identity =
                    a.putObject("identity")
                            .put("title", entry.title())
                            .put("artist_credit", entry.artistCredit())
                            .putNull("first_release_year")
                            .put("release_type", "Album");
            identity.putArray("primary_genres").addObject().put("genre_id", G).put("name", "爵士");
            a.putArray("known_review_refs");
            a.putObject("audience")
                    .put("source", "LISTENBRAINZ_SAMPLE")
                    .put("dataset_id", population.dataset())
                    .put("window", "0")
                    .put("sample_listeners", 100)
                    .put("listeners", 100);
        }
        String hash = digest(accepted);
        bundle.put("digest", hash);
        bundle.putObject("counts").put("proposed", 4).put("accepted", 4).put("rejected", 0);
        bundle.putObject("binding")
                .put("candidate_bundle_digest", hash)
                .put(
                        "effective_preference_digest",
                        pref.path("effective_preference_digest").asText())
                .put("exclusion_digest", digest(JSON.createArrayNode()))
                .put("selection_config_revision", "fixture")
                .put("agent_policy_revision", policy.path("revision").asText())
                .set("recall_binding", base.path("recall_binding"));
        var context =
                new PublicationAuthority.Context(
                        new DispatchQueue.Context(e, b),
                        resolution,
                        population,
                        policy.toString(),
                        target);
        publication.register(context, bundle.toString());
        var reads = new HashMap<String, String>();
        for (String candidate : candidates.keySet()) {
            String read = UUID.randomUUID().toString(), url = "https://example.com/" + candidate;
            reads.put(candidate, read);
            var request =
                    JSON.createObjectNode()
                            .put("read_id", read)
                            .put("candidate_id", candidate)
                            .put("url", url)
                            .put("purpose", "SCREEN_RESEARCH");
            authority.authorize(e, request.toString());
            var receipt =
                    JSON.createObjectNode()
                            .put("read_id", read)
                            .put("candidate_id", candidate)
                            .put("purpose", "SCREEN_RESEARCH")
                            .put("requested_url", url)
                            .put("final_url", url)
                            .put("publisher_key", "example.com")
                            .put("status", "READ_OK")
                            .putNull("failure_reason")
                            .put("read_at", Instant.now().toString())
                            .put("content_digest", "sha256:" + "2".repeat(64))
                            .put("excerpt_digest", "sha256:" + "3".repeat(64))
                            .put("text_chars", 1000)
                            .putNull("near_duplicate_of")
                            .put("retrieval_mode", "NETWORK");
            receipt.putObject("checks").put("mentions_title", true).put("mentions_artist", true);
            var payload = JSON.createObjectNode();
            payload.putArray("items").add(receipt);
            authority.receipts(e, payload.toString());
        }
        String invocation = e.execution() + ":selection";
        var grant = JSON.createObjectNode();
        var call =
                grant.putArray("items")
                        .addObject()
                        .put("invocation_id", invocation)
                        .put("attempt", 1)
                        .put("purpose", "SELECT")
                        .put("cost_class", "MODEL")
                        .put("lane", "FOREGROUND")
                        .put("run_id", run)
                        .putNull("candidate_id")
                        .put("model_binding_ref", "test-model")
                        .put("max_input_tokens", 100)
                        .put("max_output_tokens", 100)
                        .put("input_digest", "sha256:" + "4".repeat(64));
        call.putArray("read_ids");
        budget.grants(b, grant.toString());
        budget.settle(
                e.execution(),
                JSON.createObjectNode()
                        .put("invocation_id", invocation)
                        .put("receipt_id", e.execution() + ":usage")
                        .put("outcome", "COMPLETED")
                        .put("finish", "COMPLETE")
                        .put("input_tokens", 5)
                        .put("cached_input_tokens", 0)
                        .put("output_tokens", 5)
                        .putNull("provider_request_ref")
                        .toString());
        return new Flow(context, bundle, reads, invocation);
    }

    ObjectNode delivery(Flow f, String kind, JsonNode payload, String suffix) {
        var e = f.context().execution().evidence();
        var d =
                JSON.createObjectNode()
                        .put("delivery_id", e.execution() + ":" + suffix)
                        .put("kind", kind)
                        .put("round_id", e.round())
                        .put("run_id", f.context().execution().budget().run());
        d.set("binding", f.bundle().path("binding"));
        d.putObject("versions").putArray("prompt_versions").add("test");
        ((ObjectNode) d.path("versions")).putArray("model_binding_refs").add("test-model");
        d.set("payload", payload);
        d.put("payload_digest", digest(d.path("payload")));
        return d;
    }

    ObjectNode batch(Flow f, int count, String mode) {
        var p =
                JSON.createObjectNode()
                        .put("batch_kind", "FIRST")
                        .put("outcome", count == 0 ? "NO_MATCH" : "DISPLAYABLE")
                        .put("reason_code", count == 0 ? "no_match" : null)
                        .put("analysis_mode", mode)
                        .putNull("timing");
        p.putArray("input_issues");
        var select =
                p.putObject("selection")
                        .put("status", mode.equals("PERSONALIZED") ? "COMPLETED" : "NOT_RUN")
                        .put("invocation_id", f.invocation());
        var order = select.putArray("order");
        var screenOrder = p.putArray("screen_order");
        var cards = p.putArray("cards");
        var screens = p.putArray("screenings");
        for (int n = 1; n <= count; n++) {
            String id = album(n), read = f.reads().get(id);
            order.addObject().put("candidate_id", id).put("verdict", "recommend");
            screenOrder.add(id);
            var screen =
                    screens.addObject()
                            .put("candidate_id", id)
                            .put("recall_rank", n)
                            .put("research_status", "QUALIFIED_READS")
                            .put("disposition", "SHORTLISTED")
                            .putNull("reason_code")
                            .putNull("brief");
            screen.putArray("read_assessments");
            screen.putArray("qualified_read_ids").add(read);
            screen.putObject("screen")
                    .put("status", "OK")
                    .put("score", 5)
                    .put("invocation_id", f.invocation())
                    .put("prompt_version", "test");
            var card =
                    cards.addObject()
                            .put("candidate_id", id)
                            .put(
                                    "selection_basis",
                                    mode.equals("PERSONALIZED") ? "SELECTED" : "SCREEN_ONLY")
                            .put("long_review_state", "GENERATING");
            card.putObject("short_review")
                    .put("text", "这张专辑以细腻节奏和清晰旋律展开")
                    .put("invocation_id", f.invocation())
                    .put("prompt_version", "test")
                    .putArray("citations")
                    .addObject()
                    .put("marker", "R1")
                    .put("read_id", read);
            card.putObject("admission")
                    .put("screen_score", 5)
                    .put("screen_invocation_id", f.invocation())
                    .putArray("qualified_read_ids")
                    .add(read);
        }
        p.putObject("pending")
                .put("long_reviews_generating", count)
                .put("deferred_candidates", 0)
                .put("supply_state", "R_EXHAUSTED");
        return p;
    }

    JsonNode publish(Flow f, int count) throws Exception {
        return JSON.readTree(
                publication.deliver(
                        f.context(),
                        delivery(f, "CARD_BATCH", batch(f, count, "PERSONALIZED"), "cards")
                                .toString()));
    }

    @Test
    void actualPythonClientConsumesTransactionalDeliveryReceipt() throws Exception {
        var f = flow(2);
        var payload = delivery(f, "CARD_BATCH", batch(f, 3, "PERSONALIZED"), "python");
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/",
                exchange -> {
                    try {
                        if (!"Bearer be011-loopback"
                                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            exchange.sendResponseHeaders(401, -1);
                            return;
                        }
                        var message = JSON.readTree(exchange.getRequestBody().readAllBytes());
                        String value =
                                publication.deliver(
                                        f.context(), message.path("payload").toString());
                        var response =
                                JSON.createObjectNode()
                                        .put("schema_version", "intel.v1")
                                        .put("contract_revision", "0.30")
                                        .put("request_id", message.path("request_id").asText())
                                        .put("message_type", "DeliveryResult");
                        response.putObject("trace_context")
                                .put("trace_id", "test")
                                .put("span_id", "test");
                        response.set("payload", JSON.readTree(value));
                        byte[] bytes = JSON.writeValueAsBytes(response);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception e) {
                        exchange.sendResponseHeaders(500, -1);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        String script =
                """
            import asyncio,json,sys
            from auralis_intelligence.agents.http import ControlHTTPClient
            from auralis_intelligence.contracts import validate_definition
            async def run():
                p=json.load(sys.stdin);validate_definition('Delivery',p)
                client=ControlHTTPClient(sys.argv[1],'be011-loopback')
                try:
                    result=await client.request(sys.argv[2],'delivery',p)
                    assert len(result['published_candidate_ids'])==2,result
                    assert result==await client.request(sys.argv[2],'delivery',p)
                finally:await client.close()
            asyncio.run(run())
            """;
        try {
            var command =
                    new ProcessBuilder(
                            System.getenv("AURALIS_RECALL_PYTHON"),
                            "-X",
                            "utf8",
                            "-c",
                            script,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            f.context().execution().evidence().execution());
            command.environment()
                    .put(
                            "PYTHONPATH",
                            java.nio.file.Path.of(System.getProperty("basedir"))
                                    .resolveSibling("intelligence/src")
                                    .toString());
            command.redirectError(ProcessBuilder.Redirect.INHERIT);
            command.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            var child = command.start();
            try (var input = child.getOutputStream()) {
                input.write(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void backgroundCardsWaitForAnExplicitDisplayIntent() throws Exception {
        var f = flow(1);
        var d = delivery(f, "CARD_BATCH", batch(f, 3, "PERSONALIZED"), "background");
        d.putNull("run_id");
        var result = JSON.readTree(publication.deliver(f.context(), d.toString()));
        assertTrue(result.path("published_candidate_ids").isEmpty());
        assertEquals(3, result.path("display_state").path("ready_unshown_ids").size());
        assertEquals(
                "EMPTY",
                JSON.readTree(
                                publication.state(
                                        latest(f), f.context().execution().evidence().round()))
                        .path("slots")
                        .get(0)
                        .path("status")
                        .asText());
    }

    @Test
    void zeroResultsAndUnverifiedUsageAreNotSuccessfulCards() throws Exception {
        var f = flow(2);
        var empty = batch(f, 0, "PERSONALIZED");
        empty.put("reason_code", "NO_MATCH");
        publication.deliver(f.context(), delivery(f, "CARD_BATCH", empty, "empty").toString());
        assertEquals(
                "NO_MATCH",
                JSON.readTree(
                                publication.state(
                                        latest(f), f.context().execution().evidence().round()))
                        .path("status")
                        .asText());
        var other = flow(1);
        var p = batch(other, 1, "PERSONALIZED");
        ((ObjectNode) p.path("cards").get(0).path("short_review"))
                .put("invocation_id", other.invocation() + "-unverified");
        var rejected =
                JSON.readTree(
                        publication.deliver(
                                other.context(),
                                delivery(other, "CARD_BATCH", p, "unverified").toString()));
        assertEquals("REJECTED", rejected.path("status").asText());
        assertTrue(rejected.path("published_candidate_ids").isEmpty());
    }

    @Test
    void atomicReceiptReplayAndPartialAdmission() throws Exception {
        var f = flow(2);
        var p = batch(f, 3, "PERSONALIZED");
        ((ObjectNode) p.path("cards").get(1).path("short_review").path("citations").get(0))
                .put("read_id", UUID.randomUUID().toString());
        var d = delivery(f, "CARD_BATCH", p, "mixed");
        String result = publication.deliver(f.context(), d.toString());
        assertEquals("PARTIALLY_ACCEPTED", JSON.readTree(result).path("status").asText());
        assertEquals(2, JSON.readTree(result).path("published_candidate_ids").size());
        assertEquals(
                JSON.readTree(result),
                JSON.readTree(publication.deliver(f.context(), d.toString())));
        var old = d.deepCopy();
        ((ObjectNode) old.path("payload")).put("reason_code", "changed");
        old.put("payload_digest", digest(old.path("payload")));
        assertThrows(Exception.class, () -> publication.deliver(f.context(), old.toString()));
    }

    @Test
    void replacementsConsumeDistinctCandidatesAndOnlyHeardWritesHistory() throws Exception {
        var f = flow(2);
        publish(f, 4);
        var s = latest(f);
        publication.operate(
                s,
                f.context().execution().evidence().round(),
                "replace",
                "REPLACE",
                "slot-0001",
                (scope, round, slots) -> {
                    throw new AssertionError();
                });
        assertTrue(tx.write(() -> sessions.heardAlbums(latest(f))).isEmpty());
        publication.operate(
                latest(f),
                f.context().execution().evidence().round(),
                "heard",
                "HEARD",
                "slot-0002",
                (scope, round, slots) -> {
                    throw new AssertionError();
                });
        assertEquals(
                List.of(UUID.fromString(album(2))),
                tx.write(() -> sessions.heardAlbums(latest(f))));
        var state =
                JSON.readTree(
                        publication.state(latest(f), f.context().execution().evidence().round()));
        assertNotEquals(
                state.path("slots").get(0).path("card").path("candidate_id"),
                state.path("slots").get(1).path("card").path("candidate_id"));
    }

    @Test
    void queueRejectionRollsBackSlotHeardAndRevision() throws Exception {
        var f = flow(1);
        publish(f, 1);
        var s = latest(f);
        String before = publication.state(s, f.context().execution().evidence().round());
        assertThrows(
                PersistenceRejected.class,
                () ->
                        publication.operate(
                                s,
                                f.context().execution().evidence().round(),
                                "queue",
                                "HEARD",
                                "slot-0001",
                                (scope, r, slots) -> {
                                    throw new PersistenceRejected("QUEUE_FULL");
                                }));
        assertEquals(
                JSON.readTree(before),
                JSON.readTree(publication.state(s, f.context().execution().evidence().round())));
        assertTrue(tx.write(() -> sessions.heardAlbums(s)).isEmpty());
    }

    @Test
    void concurrentReplacementHasOneRevisionWinner() throws Exception {
        var f = flow(1);
        publish(f, 4);
        var scope = latest(f);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Future<Boolean>>();
            for (int n = 0; n < 2; n++) {
                String command = "race-" + n;
                calls.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        publication.operate(
                                                scope,
                                                f.context().execution().evidence().round(),
                                                command,
                                                "REPLACE",
                                                "slot-0001",
                                                (s, r, slots) -> null);
                                        return true;
                                    } catch (PersistenceRejected e) {
                                        return false;
                                    }
                                }));
            }
            gate.countDown();
            int winners = 0;
            for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
    }

    @Test
    void longReviewIsIndependentAndOldGenerationCannotOverwrite() throws Exception {
        var f = flow(1);
        String longCall = f.invocation() + "-long";
        var grant = JSON.createObjectNode();
        var call =
                grant.putArray("items")
                        .addObject()
                        .put("invocation_id", longCall)
                        .put("attempt", 1)
                        .put("purpose", "WRITE_LONG")
                        .put("cost_class", "MODEL")
                        .put("lane", "BACKGROUND")
                        .putNull("run_id")
                        .put("candidate_id", album(1))
                        .put("model_binding_ref", "test-model")
                        .put("max_input_tokens", 100)
                        .put("max_output_tokens", 100)
                        .put("input_digest", "sha256:" + "9".repeat(64));
        call.putArray("read_ids");
        budget.grants(f.context().execution().budget(), grant.toString());
        budget.settle(
                f.context().execution().evidence().execution(),
                JSON.createObjectNode()
                        .put("invocation_id", longCall)
                        .put("receipt_id", longCall + ":usage")
                        .put("outcome", "COMPLETED")
                        .put("finish", "COMPLETE")
                        .put("input_tokens", 5)
                        .put("cached_input_tokens", 0)
                        .put("output_tokens", 5)
                        .putNull("provider_request_ref")
                        .toString());

        publish(f, 2);
        var payload =
                JSON.createObjectNode()
                        .put("candidate_id", album(1))
                        .put("status", "READY")
                        .put("han_char_count", 2)
                        .put("prompt_version", "test")
                        .putNull("reason_code");
        payload.putObject("sections")
                .put("production", "制作")
                .put("instrumentation", "")
                .put("vocals", "")
                .put("mood", "");
        payload.putArray("citations")
                .addObject()
                .put("marker", "R1")
                .put("read_id", f.reads().get(album(1)));
        payload.putArray("extra_read_ids");
        payload.putArray("missing_sections");
        payload.putArray("invocation_ids").add(longCall);
        publication.deliver(f.context(), delivery(f, "LONG_REVIEW", payload, "long").toString());
        assertEquals(
                "READY",
                JSON.readTree(
                                publication.longReview(
                                        latest(f),
                                        f.context().execution().evidence().round(),
                                        album(1)))
                        .path("status")
                        .asText());
        String nextRun = UUID.randomUUID().toString();
        publication.regenerate(
                latest(f),
                f.context().execution().evidence().round(),
                album(1),
                (s, r, slots) -> {
                    discovery.createIntent(
                            s,
                            r,
                            nextRun,
                            "regenerate",
                            sessions.inputRevision(s),
                            "sha256:" + "8".repeat(64),
                            Instant.now(),
                            Instant.now().plusSeconds(30));
                    return nextRun;
                });
        assertThrows(
                PersistenceRejected.class,
                () ->
                        publication.deliver(
                                f.context(),
                                delivery(f, "LONG_REVIEW", payload, "late-long").toString()));
    }

    @Test
    void basicPartialNoMatchAndCancellationRemainDistinct() throws Exception {
        var f = flow(3);
        publication.deliver(
                f.context(), delivery(f, "CARD_BATCH", batch(f, 1, "BASIC"), "basic").toString());
        var state =
                JSON.readTree(
                        publication.state(latest(f), f.context().execution().evidence().round()));
        assertEquals("BASIC", state.path("analysis_mode").asText());
        assertEquals("PARTIAL", state.path("status").asText());
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    app.getBean(SessionExecutions.class)
                            .invalidate(List.of(latest(f).sessionId()), "CANCELLED");
                    return null;
                });
        var late = JSON.createObjectNode().put("state", "PAUSED").put("reason_code", "cancelled");
        late.putObject("counts")
                .put("unresearched", 0)
                .put("reserve", 0)
                .put("in_flight", 0)
                .put("ready_unshown_known", 0);
        var rejected =
                assertThrows(
                        PersistenceRejected.class,
                        () ->
                                publication.deliver(
                                        f.context(),
                                        delivery(f, "SUPPLY_STATUS", late, "late").toString()));
        assertEquals("EXECUTION_GONE", rejected.getMessage());
    }
}
