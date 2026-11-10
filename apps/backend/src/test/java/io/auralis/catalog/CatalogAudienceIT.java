package io.auralis.catalog;

import static io.auralis.catalog.application.CatalogJson.*;
import static org.junit.jupiter.api.Assertions.*;

import io.auralis.catalog.adapters.*;
import io.auralis.catalog.api.*;
import io.auralis.catalog.application.CandidateValidation;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "AURALIS_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogAudienceIT {
    static final String MANIFEST = "a".repeat(64), HASH = "sha256:" + "b".repeat(64);

    static UUID id(int n) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", n));
    }

    JdbcTemplate jdbc;
    FullAudienceImport importer;
    PostgresCatalogQuery catalog;
    PostgresGenreQuery genres;
    PostgresAudienceQuery audience;
    CandidateValidation validation;
    Path artifact;
    AudienceQuery.Binding binding;

    @BeforeAll
    void prepare() throws Exception {
        String base = System.getenv("AURALIS_TEST_POSTGRES_URL"),
                password = System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD");
        assertTrue(base.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, "postgres", password));
        admin.execute("CREATE DATABASE be007_catalog");
        var ds =
                new DriverManagerDataSource(
                        base.replace("/auralis_core_verify", "/be007_catalog"),
                        "postgres",
                        password);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO ingestion.dataset(manifest_sha256,layout_sha256,purpose,manifest,status) VALUES(?,?,'private-development','{}','IMPORTING')",
                MANIFEST,
                MANIFEST);
        jdbc.update(
                "INSERT INTO catalog.genre VALUES(1,1,?,'Rock',''),(1,2,?,'Indie Rock','')",
                id(101),
                id(102));
        jdbc.update("INSERT INTO catalog.genre_alias VALUES(1,1,2,'独立摇滚','独立摇滚','zh',NULL,true)");
        jdbc.update("INSERT INTO catalog.genre_relation VALUES(1,1,1,2,'subgenre',now())");
        jdbc.update(
                "INSERT INTO catalog.artist VALUES(1,1,?,'Artist','Artist','',NULL,now())",
                id(201));
        for (int n = 1; n <= 3; n++) {
            jdbc.update(
                    "INSERT INTO catalog.album(dataset_id,id,mbid,title,comment,source_credit_id,primary_type,first_year,source_updated) VALUES(1,?,?,?,'',1,?,2000,now())",
                    n,
                    id(n),
                    "专辑 " + n,
                    n == 3 ? null : "EP");
            jdbc.update("INSERT INTO catalog.album_credit VALUES(1,?,0,1,'Artist','')", n);
            jdbc.update("INSERT INTO catalog.album_genre VALUES(1,?,2,1,1,now())", n);
        }
        jdbc.update("INSERT INTO catalog.album_secondary_type VALUES(1,2,'Live')");
        jdbc.update("INSERT INTO catalog.album_redirect VALUES(1,?,2)", id(20));
        jdbc.execute(
                "INSERT INTO statistics.observation SELECT 1,g,'album',0,g,3,3,0,1,1,'synthetic',g,repeat('b',64) FROM generate_series(1,60) g");
        jdbc.execute(
                "INSERT INTO statistics.album_listen(dataset_id,observation_id,album_id,listen_count,source_items) SELECT 1,g,1,7,1 FROM generate_series(1,49) g");
        jdbc.execute(
                "INSERT INTO statistics.album_listen(dataset_id,observation_id,album_id,listen_count,source_items) SELECT 1,g,2,20,1 FROM generate_series(1,50) g");
        jdbc.execute(
                "INSERT INTO statistics.album_popularity VALUES(1,0,1,49,343,1,1),(1,0,2,50,1000,1,1)");
        jdbc.execute(
                "INSERT INTO statistics.observation SELECT 1,g+100,'album',2025,g,3,3,1735689600,1767225600,1,'synthetic-year',g,repeat('b',64) FROM generate_series(1,50) g");
        jdbc.execute(
                "INSERT INTO statistics.album_listen(dataset_id,observation_id,album_id,listen_count,source_items) SELECT 1,g+100,2,20,1 FROM generate_series(1,50) g");
        jdbc.execute("INSERT INTO statistics.album_popularity VALUES(1,2025,2,50,1000,1,1)");
        jdbc.execute("UPDATE ingestion.dataset SET status='ACTIVE' WHERE id=1");
        jdbc.execute("INSERT INTO ingestion.active_dataset VALUES(true,1)");
        importer =
                new FullAudienceImport(
                        jdbc, jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        artifact = Files.createTempDirectory("be007-").resolve("audience.json");
        var receipt = importer.export(1, 0, MANIFEST, artifact);
        importer.admit(artifact, receipt.path("digest").asText(), 1, MANIFEST, "0");
        binding =
                new AudienceQuery.Binding(
                        "core:" + MANIFEST, "0", 50, receipt.path("digest").asText());
        var taxonomy = TaxonomyFixtures.load(jdbc);
        catalog = new PostgresCatalogQuery(jdbc, taxonomy);
        genres = new PostgresGenreQuery(catalog, taxonomy);
        audience = new PostgresAudienceQuery(jdbc, new PostgresAudienceStore(jdbc));
        validation = new CandidateValidation(catalog, audience, genres);
    }

    @Test
    void authoritativeTaxonomyFiltersAlbumsAndReplacesDatabaseParents() throws Exception {
        var document = JSON.readTree(TaxonomyFixtures.write(jdbc).toFile());
        var rows = (com.fasterxml.jackson.databind.node.ArrayNode) document.path("genres");
        // Keep the child, remove its old MB parent and attach it to a source-only RYM parent.
        var child = rows.get(1).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) child)
                .putArray("parents")
                .add("rym:trusted");
        rows.removeAll();
        rows.add(child);
        var root = rows.addObject().put("id", "rym:trusted").put("name", "Trusted parent");
        root.putArray("parents");
        root.putArray("aliases");
        var taxonomy = new GenreTaxonomy(document);
        var filteredCatalog = new PostgresCatalogQuery(jdbc, taxonomy);
        var filteredGenres = new PostgresGenreQuery(filteredCatalog, taxonomy);
        assertEquals(
                Set.of("rym:trusted", id(102).toString()),
                filteredGenres.descendants(1, Set.of("rym:trusted")));
        assertEquals("rym:trusted", filteredGenres.search(1, null, null, 0, 50).getFirst().id());
        assertTrue(filteredGenres.nodes(1, Set.of(id(101).toString())).isEmpty());
        // A taxonomy that excludes the album's only raw genre must return an empty list.
        rows.remove(0);
        var emptyGenresCatalog = new PostgresCatalogQuery(jdbc, new GenreTaxonomy(document));
        assertTrue(emptyGenresCatalog.albums(1, List.of(id(3))).get(id(3)).genres().isEmpty());
    }

    @Test
    void fullCountsVersionAndUnknown() {
        assertEquals(
                Map.of(id(1), 49L, id(2), 50L, id(3), 0L),
                audience.audience(1, binding, List.of(id(1), id(2), id(3), id(999))));
        assertThrows(
                Exception.class,
                () ->
                        audience.audience(
                                1,
                                new AudienceQuery.Binding(
                                        binding.dataset(), "2025", 50, binding.snapshotDigest()),
                                List.of(id(2))));
        assertThrows(Exception.class, () -> audience.audience(2, binding, List.of(id(2))));
        assertThrows(
                Exception.class,
                () ->
                        audience.audience(
                                1,
                                new AudienceQuery.Binding(
                                        "train", "0", 50, binding.snapshotDigest()),
                                List.of(id(2))));
    }

    @Test
    void searchUsesOnlyTheActiveAdmittedAudienceAndClearsRetiredCache() {
        var expected = audience.audience(1, binding, List.of(id(2))).get(id(2));
        assertEquals(expected, audience.searchPopularity().get(id(2).toString()));
        jdbc.update(
                "UPDATE discovery.audience_snapshot SET active=false WHERE id=?",
                binding.snapshotDigest());
        try {
            assertTrue(audience.searchPopularity().isEmpty());
        } finally {
            jdbc.update(
                    "UPDATE discovery.audience_snapshot SET active=true WHERE id=?",
                    binding.snapshotDigest());
        }
        assertEquals(expected, audience.searchPopularity().get(id(2).toString()));
    }

    @Test
    void identitiesRedirectActualTypesAndGenreHierarchy() {
        var rows = catalog.albums(1, List.of(id(20), id(2), id(3), id(999)));
        assertEquals(rows.get(id(2)), rows.get(id(20)));
        assertNull(rows.get(id(3)).primaryType());
        assertEquals("EP", rows.get(id(2)).primaryType());
        assertEquals(List.of("Live"), rows.get(id(2)).secondaryTypes());
        assertEquals("Artist", rows.get(id(2)).artistCredit());
        assertFalse(rows.containsKey(id(999)));
        assertEquals(
                Set.of(id(101).toString(), id(102).toString()),
                genres.descendants(1, Set.of(id(101).toString())));
        assertEquals(id(102).toString(), genres.search(1, "独立", null, 0, 20).getFirst().id());
        assertEquals(
                id(102).toString(),
                genres.search(1, null, id(101).toString(), 0, 20).getFirst().id());
    }

    CandidateValidation.Context context(Set<String> forbidden) {
        var recall =
                JSON.createObjectNode()
                        .put("strategy_revision", "A-V2")
                        .put("data_snapshot_id", "train-74547")
                        .put("window", "0")
                        .put("weights_digest", HASH);
        return new CandidateValidation.Context(
                1,
                binding,
                50,
                recall,
                HASH,
                HASH,
                HASH,
                "selection-v1",
                "policy-v1",
                Set.of(),
                Set.of(id(101).toString()),
                forbidden,
                Set.of(),
                Set.of());
    }

    @Test
    void proposalPreservesRanksAndChecksDigestConstraintsAndDeduplication() throws Exception {
        var ctx = context(Set.of());
        var proposal =
                JSON.createObjectNode()
                        .put("input_digest", HASH)
                        .put("effective_preference_digest", HASH)
                        .put("exclusion_digest", HASH)
                        .put("requested_count", 4)
                        .put("returned_count", 4)
                        .put("execution_status", "COMPLETE")
                        .put("raw_reason", "complete")
                        .put("shortfall", 0)
                        .put("truncated", false);
        proposal.set("recall_binding", ctx.recallBinding());
        proposal.putArray("source_dependencies").add("audience:" + binding.snapshotDigest());
        var rows = proposal.putArray("candidates");
        int rank = 0;
        for (int n : new int[] {1, 20, 2, 3})
            rows.addObject().put("release_group_id", id(n).toString()).put("recall_rank", ++rank);
        proposal.put("proposal_digest", contentDigest(proposal, "proposal_digest"));
        var bundle = validation.validate("bundle", proposal, ctx);
        assertEquals(1, bundle.path("accepted").size());
        assertEquals(2, bundle.path("accepted").get(0).path("recall_rank").asInt());
        assertEquals(
                id(2).toString(), bundle.path("accepted").get(0).path("release_group_id").asText());
        assertEquals("DUPLICATE_IDENTITY", bundle.path("rejected").get(1).path("reason").asText());
        assertEquals(digest(bundle.path("accepted")), bundle.path("digest").asText());
        var python = System.getenv("AURALIS_RECALL_PYTHON");
        assertNotNull(python);
        var input = Files.createTempFile("be007-wire-", ".json");
        JSON.writeValue(input.toFile(), bundle);
        var process =
                new ProcessBuilder(
                        python,
                        "-c",
                        "import json,sys; from auralis_intelligence.contracts import validate_definition,digest; d=json.load(open(sys.argv[1],encoding='utf-8')); validate_definition('CandidateBundle',d); assert digest(d['accepted'])==d['digest']; assert d['accepted'][0]['proposed_release_group_id']!=d['accepted'][0]['release_group_id']",
                        input.toString());
        process.environment()
                .put(
                        "PYTHONPATH",
                        Path.of(System.getProperty("basedir"))
                                .resolveSibling("intelligence/src")
                                .toString());
        var child = process.inheritIO().start();
        assertTrue(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        Files.delete(input);

        assertTrue(
                validation
                        .validate("bundle", proposal, context(Set.of(id(101).toString())))
                        .path("accepted")
                        .isEmpty());
        proposal.put("input_digest", "sha256:" + "c".repeat(64));
        assertThrows(Exception.class, () -> validation.validate("bundle", proposal, ctx));
    }

    @Test
    void tamperedResignedAndIncompleteArtifactsNeverReplaceActive() throws Exception {
        var bad = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(artifact.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) bad.path("counts").get(0))
                .put("listeners", 50);
        bad.put("digest", contentDigest(bad, "digest"));
        var path = artifact.resolveSibling("tampered.json");
        JSON.writeValue(path.toFile(), bad);
        assertThrows(
                Exception.class,
                () -> importer.admit(path, bad.path("digest").asText(), 1, MANIFEST, "0"));
        assertEquals(
                binding.snapshotDigest(),
                jdbc.queryForObject(
                        "SELECT id FROM discovery.audience_snapshot WHERE active", String.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.audience_snapshot", Integer.class));
        importer.admit(artifact, binding.snapshotDigest(), 1, MANIFEST, "0");
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.audience_count", Integer.class));
    }

    @Test
    void databaseFailureRollsBackStagingAndKeepsPreviousActivation() throws Exception {
        var year = artifact.resolveSibling("year.json");
        var receipt = importer.export(1, 2025, MANIFEST, year);
        jdbc.execute(
                "CREATE FUNCTION discovery.be007_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.listening_window='2025' THEN RAISE EXCEPTION 'INJECTED_IMPORT_FAILURE'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER be007_fail BEFORE INSERT ON discovery.audience_count FOR EACH ROW EXECUTE FUNCTION discovery.be007_fail()");
        try {
            assertThrows(
                    Exception.class,
                    () ->
                            importer.admit(
                                    year, receipt.path("digest").asText(), 1, MANIFEST, "2025"));
            assertEquals(
                    binding.snapshotDigest(),
                    jdbc.queryForObject(
                            "SELECT id FROM discovery.audience_snapshot WHERE active",
                            String.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.audience_snapshot", Integer.class));
            assertEquals(
                    3,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.audience_count", Integer.class));
        } finally {
            jdbc.execute("DROP TRIGGER be007_fail ON discovery.audience_count");
            jdbc.execute("DROP FUNCTION discovery.be007_fail()");
        }
    }
}
