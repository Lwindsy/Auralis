package io.auralis.publication;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.bootstrap.*;
import io.auralis.catalog.api.*;
import io.auralis.catalog.application.CandidateValidation;
import io.auralis.discovery.api.*;
import io.auralis.persistence.ShortTransactions;
import io.auralis.session.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class ProductApiFixture extends PublicationFixture {
    static final String ROCK = "00000000-0000-0000-0000-000000000102";
    final String origin =
            System.getProperty("auralis.test.product-origin", "http://127.0.0.1:8080");
    final List<Browser> browsers = new ArrayList<>();
    SyntheticExecutionPeer peer;
    ObjectNode settings;
    Path configFile, temp;
    String base;

    @Override
    int albumCount() {
        return 12;
    }

    @Override
    void extraCatalog() {
        admin.update("INSERT INTO catalog.genre VALUES(?,2,?::uuid,'摇滚','')", dataset, ROCK);
        for (int n = 5; n <= 12; n++) {
            admin.update(
                    "INSERT INTO catalog.album(dataset_id,id,mbid,title,comment,source_credit_id,primary_type,source_updated) VALUES(?,?,?::uuid,?,'',1,'Album',now())",
                    dataset,
                    n,
                    album(n),
                    "推荐专辑" + n);
            admin.update("INSERT INTO catalog.album_credit VALUES(?,?,0,1,'艺人','')", dataset, n);
            admin.update("INSERT INTO catalog.album_genre VALUES(?,?,1,1,1,now())", dataset, n);
        }
    }

    @BeforeAll
    void productSetup() throws Exception {
        settings = configuration();
        temp = Files.createTempDirectory("auralis-be003-");
        configFile = temp.resolve("recommendation.json");
        Files.writeString(configFile, settings.toPrettyString());
        var ids = new ArrayList<String>();
        for (int n = 1; n <= albumCount(); n++) ids.add(album(n));
        peer = new SyntheticExecutionPeer(settings, ids);
        bootProduct();
    }

    ObjectNode configuration() throws Exception {
        var template =
                JSON.readTree(
                                Path.of(System.getProperty("basedir"))
                                        .resolveSibling(
                                                "intelligence/tests/fixtures/agent-contract-cases.json")
                                        .toFile())
                        .get(0)
                        .path("message")
                        .path("payload");
        var result = JSON.createObjectNode();
        for (String key :
                List.of(
                        "agent_policy",
                        "selection_config",
                        "model_bindings",
                        "recall_binding",
                        "web_guards",
                        "capabilities")) result.set(key, template.path(key).deepCopy());
        ((ObjectNode) result.path("selection_config"))
                .put("recall_target_count", 4)
                .put("shortlist_target_count", 4)
                .put("recommendation_target_count", 2);
        result.set("stage_capacity", result.path("selection_config").deepCopy());
        ((ObjectNode) result.path("model_bindings"))
                .set("long_write", result.path("model_bindings").path("write").deepCopy());
        result.path("model_bindings")
                .forEach(
                        n ->
                                ((ObjectNode) n)
                                        .put("max_input_tokens", 1000)
                                        .put("max_output_tokens", 1000));
        var policy = (ObjectNode) result.path("agent_policy");
        ((ObjectNode) policy.path("writing")).put("short_min_han", 40).put("short_max_han", 180);
        policy.put("digest", contentDigest(policy, "digest"));
        result.putObject("budget_limits")
                .put("session_cap_microusd", 1000000)
                .put("round_cap_microusd", 1000000)
                .put("model_calls", 1000)
                .put("search_calls", 100)
                .put("read_calls", 1000)
                .putObject("lane_caps")
                .put("FOREGROUND", 500000)
                .put("BACKGROUND", 500000);
        result.putObject("prices")
                .set(
                        "fixture",
                        JSON.valueToTree(
                                new io.auralis.budget.api.BudgetAuthority.Price(
                                        "fixture", 1000000, 1000000, 1000000, 1, 1000, 1000)));
        result.put("reference_revision", "catalog:" + dataset).put("idle_close_ms", 5000);
        result.putArray("parser_revisions").add("fixture");
        result.putObject("reference_genres").put(G, "爵士").put(ROCK, "摇滚");
        result.set("audience_binding", JSON.valueToTree(population));
        return result;
    }

    void bootProduct() {
        if (app != null) app.close();
        var application =
                new SpringApplication(OnlineApplication.class, CuratedTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        var props = new HashMap<String, Object>();
        props.put("auralis.jdbc-url", System.getenv("AURALIS_TEST_POSTGRES_URL"));
        props.put("auralis.database-user", "be011_online");
        props.put("auralis.database-password", "be011-synthetic-password");
        props.put("auralis.product-origin", origin);
        props.put("auralis.python-url", peer.url());
        props.put("auralis.service-token", SyntheticExecutionPeer.TOKEN);
        props.put("auralis.contract-revision", "0.30");
        props.put(
                "auralis.subject-secret",
                "be003-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
        props.put("auralis.allow-insecure-cookie", true);
        props.put("auralis.session-cleanup-enabled", false);
        props.put("auralis.dispatch-enabled", true);
        props.put("auralis.recommendation-config", configFile.toString());
        props.put("server.port", 0);
        application.setDefaultProperties(props);
        app =
                application.run(
                        props.entrySet().stream()
                                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                                .toArray(String[]::new));
        jdbc = app.getBean(JdbcTemplate.class);
        tx = app.getBean(ShortTransactions.class);
        sessions = app.getBean(SessionStore.class);
        discovery = app.getBean(DiscoveryStore.class);
        subjects = app.getBean(SubjectSession.class);
        publication = app.getBean(io.auralis.publication.api.PublicationAuthority.class);
        budget = app.getBean(io.auralis.budget.api.BudgetAuthority.class);
        authority = app.getBean(io.auralis.evidence.api.EvidenceAuthority.class);
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        peer.controlBase = base;
    }

    @AfterEach
    void cleanProduct() throws Exception {
        for (var browser : browsers)
            try {
                subjects.erase(browser.token(), browser.csrf);
            } catch (RuntimeException ignored) {
            }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!app.getBean(DispatchQueue.class).active().isEmpty() && System.nanoTime() < until)
            Thread.sleep(50);
        peer.cancelAll();
        for (var job : app.getBean(DispatchQueue.class).active())
            app.getBean(DispatchQueue.class).release(job, "INTERRUPTED");
        subjects.cleanup(100);
        browsers.clear();
        peer.hold = false;
        peer.holdReports = false;
        peer.failures.clear();
        peer.longDelayMs = 600;
    }

    @AfterAll
    void closeProduct() throws Exception {
        if (peer != null) peer.close();
        if (configFile != null) Files.deleteIfExists(configFile);
        if (temp != null) Files.deleteIfExists(temp);
    }

    ObjectNode userInput(String text) {
        var input =
                JSON.createObjectNode()
                        .put("mode", "explore")
                        .put("request_text", text)
                        .put("language", "zh-CN");
        input.putArray("liked_genres")
                .addObject()
                .put("genre_id", G)
                .put("strength", "special")
                .put("focused", true);
        input.putArray("disliked_genre_ids");
        input.putArray("liked_albums");
        return input;
    }

    record Reply(int status, JsonNode body) {}

    final class Browser implements AutoCloseable {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(1))
                        .build();
        String csrf;
        JsonNode state;

        Reply request(String method, String path, JsonNode body, String key) throws Exception {
            var request =
                    HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
            if (method.equals("GET")) request.GET();
            else {
                request.header("Origin", origin).header("Content-Type", "application/json");
                if (csrf != null) request.header("X-CSRF-Token", csrf);
                if (key != null) request.header("Idempotency-Key", key);
                request.method(
                        method,
                        HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            }
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            JsonNode value = JSON.readTree(response.body());
            String definition =
                    response.statusCode() >= 400
                            ? "ProductError"
                            : value.has("csrf_token")
                                    ? "BootstrapState"
                                    : value.has("candidate_id") ? "LongReviewView" : "ProductState";
            assertTrue(
                    app.getBean(WireProtocol.class).validDefinition(definition, value),
                    value.toString());
            if (definition.equals("ProductState"))
                assertEquals(
                        value.path("status").asText().equals("WAITING"),
                        value.path("admission").path("status").asText().equals("WAITING"),
                        "Product waiting status must agree with the current admission");
            if (value.has("csrf_token")) {
                csrf = value.path("csrf_token").asText();
                state = value.path("state");
            } else if (value.has("session_id")) state = value;
            return new Reply(response.statusCode(), value);
        }

        void connect() throws Exception {
            assertEquals(
                    200, request("POST", "/v1/bootstrap", JSON.createObjectNode(), null).status());
        }

        Reply submit(ObjectNode input, String key) throws Exception {
            var p =
                    JSON.createObjectNode()
                            .put("expected_revision", state.path("revision").asLong());
            p.set("expected_session_id", state.path("session_id"));
            p.set("input", input);
            return request("POST", "/v1/discovery-sessions", p, key);
        }

        JsonNode refresh() throws Exception {
            var response = request("GET", "/v1/current-state", null, null);
            assertEquals(200, response.status(), response.body().toString());
            return response.body();
        }

        JsonNode awaitState(Predicate<JsonNode> predicate) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            JsonNode value;
            do {
                value = refresh();
                if (predicate.test(value)) return value;
                Thread.sleep(50);
            } while (System.nanoTime() < until);
            fail("State timeout: " + value + " " + peer.failures);
            return null;
        }

        String session() {
            return state.path("session_id").asText();
        }

        Reply action(String suffix, String action, String key) throws Exception {
            var p =
                    JSON.createObjectNode()
                            .put("expected_revision", state.path("revision").asLong());
            if (action != null) p.put("action", action);
            return request("POST", "/v1/discovery-sessions/" + session() + "/" + suffix, p, key);
        }

        String token() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals(SubjectCookies.NAME))
                    .findFirst()
                    .orElseThrow()
                    .getValue();
        }

        public void close() {
            client.shutdownNow();
        }
    }

    Browser browser() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (jdbc.queryForObject(
                        "SELECT maintenance FROM discovery.runtime_control WHERE singleton",
                        Boolean.class)
                && System.nanoTime() < until) Thread.sleep(25);
        var b = new Browser();
        browsers.add(b);
        b.connect();
        return b;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CuratedTestConfiguration {
        @Bean
        @Primary
        CuratedRecommendations fixedList(
                CandidateValidation validator,
                DiscoveryStore discovery,
                SessionStore sessions,
                io.auralis.publication.api.PublicationAuthority publication) {
            return (scope, round, text) -> {
                try {
                    var info = discovery.roundInfo(scope, round);
                    var config = JSON.readTree(info.configuration());
                    var settings = config.path("settings");
                    var pref = JSON.readTree(text);
                    var binding =
                            JSON.treeToValue(
                                    settings.path("audience_binding"), AudienceQuery.Binding.class);
                    var excludes = new TreeMap<String, String>();
                    sessions.heardAlbums(scope).forEach(id -> excludes.put(id.toString(), "HEARD"));
                    for (var item : JSON.readTree(publication.exclusions(scope, round)))
                        excludes.putIfAbsent(
                                item.path("release_group_id").asText(),
                                item.path("reason").asText());
                    var excluded = JSON.createArrayNode();
                    excludes.forEach(
                            (id, reason) ->
                                    excluded.addObject()
                                            .put("release_group_id", id)
                                            .put("reason", reason));
                    var p =
                            JSON.createObjectNode()
                                    .put("input_digest", pref.path("input_digest").asText())
                                    .put(
                                            "effective_preference_digest",
                                            pref.path("effective_preference_digest").asText())
                                    .put("exclusion_digest", digest(excluded))
                                    .put("requested_count", 4)
                                    .put("returned_count", 4)
                                    .put("execution_status", "COMPLETE")
                                    .put("raw_reason", "fixed-test-list")
                                    .put("shortfall", 0)
                                    .put("truncated", false);
                    p.set("recall_binding", settings.path("recall_binding"));
                    p.putArray("source_dependencies").add("audience:" + binding.snapshotDigest());
                    var rows = p.putArray("candidates");
                    for (int n = 1; n <= 4; n++)
                        rows.addObject().put("release_group_id", album(n)).put("recall_rank", n);
                    p.put("proposal_digest", contentDigest(p, "proposal_digest"));
                    var forbidden = new HashSet<String>();
                    pref.path("forbidden_tags").forEach(n -> forbidden.add(n.asText()));
                    var disliked = new HashSet<String>();
                    pref.path("disliked_tags").forEach(n -> disliked.add(n.asText()));
                    var required = new HashSet<String>();
                    pref.path("required_tags").forEach(n -> required.add(n.asText()));
                    var excludedIds = new HashSet<UUID>();
                    excludes.keySet().forEach(id -> excludedIds.add(UUID.fromString(id)));
                    return validator
                            .validate(
                                    "curated-" + round,
                                    p,
                                    new CandidateValidation.Context(
                                            config.path("catalog_dataset").asLong(),
                                            binding,
                                            settings.path("recall_binding"),
                                            pref.path("input_digest").asText(),
                                            pref.path("effective_preference_digest").asText(),
                                            digest(excluded),
                                            settings.path("selection_config")
                                                    .path("revision")
                                                    .asText(),
                                            settings.path("agent_policy").path("revision").asText(),
                                            excludedIds,
                                            required,
                                            forbidden,
                                            disliked,
                                            Set.of()))
                            .toString();
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            };
        }
    }
}
