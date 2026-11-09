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
abstract class PublicationFixture {
    static final String G = "00000000-0000-0000-0000-000000000101",
            A = "00000000-0000-0000-0000-000000000201";
    final String MANIFEST = UUID.randomUUID().toString().replace("-", "").repeat(2);
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
        assertEquals(
                0,
                admin.queryForObject(
                        "SELECT count(*) FROM discovery.execution_slot WHERE state<>'FREE'",
                        Integer.class));
        admin.update(
                "UPDATE discovery.runtime_control SET maintenance=false,coordinator_boot=NULL,python_boot=NULL WHERE singleton");
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
        extraCatalog();
        admin.update("UPDATE ingestion.dataset SET status='ACTIVE' WHERE id=?", dataset);
        admin.update(
                "INSERT INTO ingestion.active_dataset VALUES(true,?) ON CONFLICT(singleton) DO UPDATE SET dataset_id=EXCLUDED.dataset_id",
                dataset);
        admin.execute(
                "DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='be011_online') THEN CREATE ROLE be011_online LOGIN PASSWORD 'be011-synthetic-password'; END IF; END $$");
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
        app =
                application.run(
                        properties.entrySet().stream()
                                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                                .toArray(String[]::new));
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
                    for (int n = 1; n <= albumCount(); n++)
                        counts.put(UUID.fromString(album(n)), 100L);
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

    void extraCatalog() {}

    int albumCount() {
        return 4;
    }
}
