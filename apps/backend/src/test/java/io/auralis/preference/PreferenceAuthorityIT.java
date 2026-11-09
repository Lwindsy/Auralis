package io.auralis.preference;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.bootstrap.OnlineApplication;
import io.auralis.preference.api.*;
import io.auralis.session.api.*;
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
class PreferenceAuthorityIT {
    static final String G = "00000000-0000-0000-0000-000000000101",
            A = "00000000-0000-0000-0000-000000000201",
            X = "00000000-0000-0000-0000-000000000999";
    JdbcTemplate admin, jdbc;
    ConfigurableApplicationContext app;
    SubjectSession subjects;
    PreferenceAuthority authority;
    SessionStore sessions;
    long dataset;

    @BeforeAll
    void setup() {
        String url = System.getenv("AURALIS_TEST_POSTGRES_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        var source =
                new DriverManagerDataSource(
                        url, "postgres", System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        admin = new JdbcTemplate(source);
        dataset =
                admin.queryForObject(
                        "INSERT INTO ingestion.dataset(manifest_sha256,layout_sha256,purpose,manifest,status) VALUES(repeat('e',64),repeat('e',64),'private-development','{}','IMPORTING') RETURNING id",
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
        admin.update("UPDATE ingestion.dataset SET status='ACTIVE' WHERE id=?", dataset);
        admin.update("INSERT INTO ingestion.active_dataset VALUES(true,?)", dataset);
        admin.execute("CREATE ROLE be008_online LOGIN PASSWORD 'be008-synthetic-password'");
        admin.execute("GRANT USAGE ON SCHEMA catalog,ingestion,discovery TO be008_online");
        admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA catalog,ingestion TO be008_online");
        admin.execute(
                "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO be008_online");
        admin.execute("GRANT SELECT ON public.flyway_schema_history TO be008_online");
        var application = new SpringApplication(OnlineApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        var properties = new HashMap<String, Object>();
        properties.put("auralis.jdbc-url", url);
        properties.put("auralis.database-user", "be008_online");
        properties.put("auralis.database-password", "be008-synthetic-password");
        properties.put("auralis.product-origin", "http://127.0.0.1:8080");
        properties.put("auralis.python-url", "http://127.0.0.1:1");
        properties.put("auralis.service-token", "be008-service-synthetic-token-123456789");
        properties.put("auralis.contract-revision", "0.32");
        properties.put(
                "auralis.subject-secret",
                "be008-test-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
        properties.put("auralis.allow-insecure-cookie", true);
        properties.put("auralis.session-cleanup-enabled", false);
        application.setDefaultProperties(properties);
        app = application.run();
        subjects = app.getBean(SubjectSession.class);
        authority = app.getBean(PreferenceAuthority.class);
        sessions = app.getBean(SessionStore.class);
        jdbc = app.getBean(JdbcTemplate.class);
    }

    @AfterAll
    void close() {
        if (app != null) app.close();
    }

    record Input(
            SubjectSession.Bootstrap browser,
            SessionStore.Scope scope,
            ObjectNode raw,
            PreferenceAuthority.Context context) {}

    ObjectNode raw() {
        var raw =
                JSON.createObjectNode()
                        .put("mode", "explore")
                        .put("language", "zh")
                        .put("request_text", "🎧想听爵士");
        raw.putArray("liked_genres")
                .addObject()
                .put("genre_id", G)
                .put("strength", "special")
                .put("focused", true);
        raw.putArray("disliked_genre_ids");
        raw.putArray("liked_albums");
        return raw;
    }

    Input input(ObjectNode raw, Map<String, PreferenceAuthority.ExternalSeed> external) {
        var browser = subjects.bootstrap(null);
        raw.put("input_digest", contentDigest(raw, "input_digest"));
        var editable = raw.deepCopy();
        editable.remove("input_digest");
        var albums = editable.putArray("liked_albums");
        for (var row : raw.path("liked_albums"))
            albums.addObject()
                    .put(
                            "mb_release_group_id",
                            row.path("release_group_id").isNull()
                                    ? row.path("external").path("mb_release_group_id").asText()
                                    : row.path("release_group_id").asText())
                    .put("strength", row.path("strength").asText())
                    .put("focused", row.path("focused").asBoolean());
        var replacement =
                subjects.replace(
                        browser.credential(),
                        browser.csrf(),
                        UUID.randomUUID().toString(),
                        null,
                        0,
                        editable.toString(),
                        raw.toString());
        var context =
                context(
                        replacement.scope(),
                        UUID.randomUUID().toString(),
                        dataset,
                        "reference-1",
                        external);
        return new Input(browser, replacement.scope(), raw, context);
    }

    PreferenceAuthority.Context context(
            SessionStore.Scope scope,
            String command,
            long version,
            String reference,
            Map<String, PreferenceAuthority.ExternalSeed> external) {
        return new PreferenceAuthority.Context(
                scope, command, version, reference, Set.of("parser-1"), Map.of(G, "爵士"), external);
    }

    ObjectNode proposal(Input input) {
        var value =
                JSON.createObjectNode()
                        .put("input_digest", input.raw().path("input_digest").asText())
                        .put("reference_revision", "reference-1")
                        .put("parser_revision", "parser-1");
        value.putArray("preferences");
        value.putArray("constraints");
        value.putArray("unresolved_constraints");
        value.putArray("soft_preferences");
        return sign(value);
    }

    ObjectNode sign(ObjectNode proposal) {
        proposal.put("proposal_digest", contentDigest(proposal, "proposal_digest"));
        return proposal;
    }

    int count(Input input) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM discovery.effective_preference WHERE session_id=?",
                Integer.class,
                input.scope().sessionId());
    }

    @Test
    void persistedInputFlagsAndReferenceReceiptAreAtomicAndDoNotRenew() throws Exception {
        var input = input(raw(), Map.of());
        var p = proposal(input);
        p.withArray("preferences")
                .addObject()
                .put("id", G)
                .put("kind", "prefer")
                .put("basis", "explicit")
                .putObject("text_span")
                .put("start", 3)
                .put("end", 5);
        sign(p);
        var before = sessions.current(input.scope().subjectId()).orElseThrow();
        var result = JSON.readTree(authority.resolve(input.context(), p.toString()));
        assertEquals("special", result.path("tags").get(0).path("strength").asText());
        assertTrue(result.path("tags").get(0).path("focused").asBoolean());
        assertEquals(
                before.expiresAt(),
                sessions.current(input.scope().subjectId()).orElseThrow().expiresAt());
        assertEquals(result, JSON.readTree(authority.resolve(input.context(), p.toString())));
        assertEquals(1, count(input));
        String receipt =
                jdbc.queryForObject(
                        "SELECT result_ref::text FROM discovery.command_receipt WHERE command_id=?",
                        String.class,
                        input.context().commandId());
        assertFalse(receipt.contains("soft_preferences"));
        assertFalse(receipt.contains("爵士"));
        p.withArray("soft_preferences")
                .addObject()
                .put("preference_id", "changed")
                .put("description", "适合夜晚")
                .put("kind", "context")
                .put("basis", "explicit")
                .put("strength", "normal")
                .put("focused", false);
        sign(p);
        assertThrows(Exception.class, () -> authority.resolve(input.context(), p.toString()));
        assertEquals(1, count(input));
    }

    @Test
    void versionsSpansAndUnknownCatalogFailBeforeWriting() {
        var input = input(raw(), Map.of());
        var p = proposal(input);
        p.put("parser_revision", "unapproved");
        sign(p);
        assertThrows(
                PreferenceRejected.class, () -> authority.resolve(input.context(), p.toString()));
        p.put("parser_revision", "parser-1");
        sign(p);
        assertThrows(
                Exception.class,
                () ->
                        authority.resolve(
                                context(
                                        input.scope(),
                                        "wrong-version",
                                        dataset + 1,
                                        "reference-1",
                                        Map.of()),
                                p.toString()));
        assertThrows(
                PreferenceRejected.class,
                () ->
                        authority.resolve(
                                context(input.scope(), "wrong-ref", dataset, "wrong", Map.of()),
                                p.toString()));
        p.withArray("preferences")
                .addObject()
                .put("id", G)
                .put("kind", "prefer")
                .put("basis", "explicit")
                .putObject("text_span")
                .put("start", 4)
                .put("end", 6);
        sign(p);
        assertEquals(
                "INTERPRETATION_INVALID_SPAN",
                assertThrows(
                                PreferenceRejected.class,
                                () -> authority.resolve(input.context(), p.toString()))
                        .getMessage());
        assertEquals(0, count(input));
        var unknown = raw();
        unknown.withArray("liked_genres").removeAll();
        unknown.withArray("liked_genres")
                .addObject()
                .put("genre_id", X)
                .put("strength", "normal")
                .put("focused", false);
        var other = input(unknown, Map.of());
        assertThrows(
                PreferenceRejected.class,
                () -> authority.resolve(other.context(), proposal(other).toString()));
        assertEquals(0, count(other));
    }

    @Test
    void coreAndVerifiedExternalSeedsNeverInventRecallIds() throws Exception {
        var raw = raw();
        raw.withArray("liked_genres").removeAll();
        raw.put("request_text", "");
        raw.withArray("liked_albums")
                .addObject()
                .put("release_group_id", A)
                .putNull("external")
                .put("strength", "special")
                .put("focused", true);
        var input = input(raw, Map.of());
        var result = JSON.readTree(authority.resolve(input.context(), proposal(input).toString()));
        assertEquals(A, result.path("seeds").get(0).path("id").asText());
        assertTrue(
                result.path("soft_preferences")
                        .get(0)
                        .path("description")
                        .asText()
                        .contains("艺人 / 专辑"));
        for (boolean mapped : new boolean[] {false, true}) {
            var externalRaw = raw();
            externalRaw.withArray("liked_genres").removeAll();
            externalRaw.put("request_text", "");
            var album =
                    externalRaw
                            .withArray("liked_albums")
                            .addObject()
                            .putNull("release_group_id")
                            .put("strength", "special")
                            .put("focused", true);
            var ext = album.putObject("external").put("mb_release_group_id", X);
            var ids = ext.putArray("mapped_genre_ids");
            if (mapped) ids.add(G);
            var external =
                    Map.of(
                            X,
                            new PreferenceAuthority.ExternalSeed(
                                    "库外专辑",
                                    "外部艺人",
                                    mapped ? List.of(G) : List.of(),
                                    "reference-1"));
            var item = input(externalRaw, external);
            var resolved =
                    JSON.readTree(authority.resolve(item.context(), proposal(item).toString()));
            assertTrue(resolved.path("seeds").isEmpty());
            assertEquals(mapped, resolved.path("has_positive_signal").asBoolean());
            var unverified = input(externalRaw.deepCopy(), Map.of());
            assertThrows(
                    PreferenceRejected.class,
                    () -> authority.resolve(unverified.context(), proposal(unverified).toString()));
        }
    }

    @Test
    void parallelReplayStoresOneSnapshotAndOneReceipt() throws Exception {
        var input = input(raw(), Map.of());
        String payload = proposal(input).toString();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results =
                    pool.<String>invokeAll(
                            java.util.Collections.nCopies(
                                    8, () -> authority.resolve(input.context(), payload)));
            var first = JSON.readTree(results.getFirst().get());
            for (var result : results) assertEquals(first, JSON.readTree(result.get()));
        }
        assertEquals(1, count(input));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.command_receipt WHERE command_id=?",
                        Integer.class,
                        input.context().commandId()));
    }

    @Test
    void crossSubjectDeletedAndSupersededSessionsRejectLateProposals() {
        var a = input(raw(), Map.of());
        var b = input(raw(), Map.of());
        var scope = a.scope();
        var forged =
                new SessionStore.Scope(
                        b.scope().subjectId(),
                        scope.sessionId(),
                        scope.subjectEpoch(),
                        scope.sessionEpoch(),
                        scope.revision());
        assertThrows(
                Exception.class,
                () ->
                        authority.resolve(
                                context(forged, "forged", dataset, "reference-1", Map.of()),
                                proposal(a).toString()));
        authority.resolve(a.context(), proposal(a).toString());
        subjects.erase(a.browser().credential(), a.browser().csrf());
        assertThrows(Exception.class, () -> authority.resolve(a.context(), proposal(a).toString()));
        subjects.cleanup(100);
        subjects.cleanup(100);
        assertEquals(0, count(a));
        var editable = b.raw().deepCopy();
        editable.remove("input_digest");
        subjects.replace(
                b.browser().credential(),
                b.browser().csrf(),
                "replacement",
                b.scope().sessionId(),
                b.scope().revision(),
                editable.toString(),
                b.raw().toString());
        assertThrows(Exception.class, () -> authority.resolve(b.context(), proposal(b).toString()));
        assertEquals(0, count(b));
    }

    @Test
    void receiptFailureRollsBackTheSnapshot() {
        var input = input(raw(), Map.of());
        admin.execute(
                "CREATE FUNCTION discovery.be008_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation='preference-resolution' THEN RAISE EXCEPTION 'SYNTHETIC_RECEIPT_FAILURE'; END IF; RETURN NEW; END $$");
        admin.execute(
                "CREATE TRIGGER be008_fail BEFORE INSERT ON discovery.command_receipt FOR EACH ROW EXECUTE FUNCTION discovery.be008_fail()");
        try {
            assertThrows(
                    Exception.class,
                    () -> authority.resolve(input.context(), proposal(input).toString()));
            assertEquals(0, count(input));
        } finally {
            admin.execute("DROP TRIGGER be008_fail ON discovery.command_receipt");
            admin.execute("DROP FUNCTION discovery.be008_fail()");
        }
        authority.resolve(input.context(), proposal(input).toString());
        assertEquals(1, count(input));
    }
}
