package io.auralis.catalog;

import static io.auralis.catalog.application.CatalogJson.*;
import static org.junit.jupiter.api.Assertions.*;

import io.auralis.catalog.adapters.*;
import io.auralis.catalog.api.AudienceQuery;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit real source, read-only connections; all projection writes go to a disposable instance.
 */
@EnabledIfEnvironmentVariable(named = "AURALIS_TEST_REAL_CORE_CONFIG", matches = ".+")
class RealCatalogAudienceIT {
    @Test
    void fullExportImportAndCrossLanguageSamples() throws Exception {
        String base = System.getenv("AURALIS_TEST_POSTGRES_URL");
        assertNotNull(base);
        assertTrue(base.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        var config =
                JSON.readTree(Path.of(System.getenv("AURALIS_TEST_REAL_CORE_CONFIG")).toFile());
        assertNotEquals(base, config.path("jdbc_url").asText());
        var sourceData =
                new DriverManagerDataSource(
                        config.path("jdbc_url").asText(),
                        config.path("importer_user").asText(),
                        config.path("importer_password").asText());
        var properties = new Properties();
        properties.setProperty(
                "options",
                "-c default_transaction_read_only=on -c max_parallel_workers_per_gather=0 -c statement_timeout=600000");
        sourceData.setConnectionProperties(properties);
        var source = new JdbcTemplate(sourceData);
        var identity =
                source.queryForMap(
                        "SELECT d.id,d.manifest_sha256 FROM ingestion.dataset d JOIN ingestion.active_dataset a ON a.dataset_id=d.id");
        long dataset = ((Number) identity.get("id")).longValue();
        String manifest = (String) identity.get("manifest_sha256");
        String password = System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, "postgres", password));
        admin.execute("CREATE DATABASE be007_real");
        var targetData =
                new DriverManagerDataSource(
                        base.replace("/auralis_core_verify", "/be007_real"), "postgres", password);
        Flyway.configure()
                .dataSource(targetData)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var target = new JdbcTemplate(targetData);
        target.update(
                "INSERT INTO ingestion.dataset(id,manifest_sha256,layout_sha256,purpose,manifest,status) OVERRIDING SYSTEM VALUE VALUES(?,?,?,'private-development','{}','ACTIVE')",
                dataset,
                manifest,
                manifest);
        target.update("INSERT INTO ingestion.active_dataset VALUES(true,?)", dataset);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(targetData));
        tx.setTimeout(600);
        var importer = new FullAudienceImport(source, target, tx);
        Path output = Path.of(System.getenv("AURALIS_TEST_AUDIENCE_OUTPUT"));
        Files.createDirectories(output);
        Path artifact = output.resolve("audience.json");
        long started = System.nanoTime();
        var exported = importer.export(dataset, 0, manifest, artifact);
        long exportMs = (System.nanoTime() - started) / 1_000_000;
        started = System.nanoTime();
        importer.admit(artifact, exported.path("digest").asText(), dataset, manifest, "0");
        long importMs = (System.nanoTime() - started) / 1_000_000;
        var binding =
                new AudienceQuery.Binding(
                        "core:" + manifest,
                        "0",
                        exported.path("sample_listeners").asLong(),
                        exported.path("digest").asText());
        var audience = new PostgresAudienceQuery(target, new PostgresAudienceStore(target));
        var ids =
                new ArrayList<>(
                        target.queryForList(
                                "SELECT release_group_id FROM discovery.audience_count ORDER BY release_group_id LIMIT 997",
                                UUID.class));
        for (int count : new int[] {0, 49, 50})
            ids.add(
                    target.queryForObject(
                            "SELECT release_group_id FROM discovery.audience_count WHERE listeners=? LIMIT 1",
                            UUID.class,
                            count));
        started = System.nanoTime();
        var counts = audience.audience(dataset, binding, ids);
        long audienceMs = (System.nanoTime() - started) / 1_000_000;
        started = System.nanoTime();
        var albums = new PostgresCatalogQuery(source).albums(dataset, ids);
        long catalogMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(new HashSet<>(ids), counts.keySet());
        assertEquals(counts.keySet(), albums.keySet());
        target.execute("ANALYZE discovery.audience_count");
        String plan =
                String.join(
                        "\n",
                        target.queryForList(
                                "EXPLAIN (ANALYZE,BUFFERS) SELECT listeners FROM discovery.audience_count WHERE snapshot_id=? AND listening_window='0' AND release_group_id=?",
                                String.class,
                                binding.snapshotDigest(),
                                ids.getFirst()));
        assertTrue(plan.contains("audience_count_pkey"), plan);
        var receipt =
                JSON.createObjectNode()
                        .put("catalog_rows", exported.path("catalog_rows").asLong())
                        .put("export_ms", exportMs)
                        .put("import_ms", importMs)
                        .put("audience_1000_ms", audienceMs)
                        .put("catalog_1000_ms", catalogMs)
                        .put("audience_explain", plan);
        receipt.set(
                "binding",
                JSON.createObjectNode()
                        .put("dataset_id", binding.dataset())
                        .put("window", "0")
                        .put("sample_listeners", binding.sampleListeners())
                        .put("snapshot_digest", binding.snapshotDigest()));
        var samples = receipt.putObject("samples");
        counts.forEach((id, n) -> samples.put(id.toString(), n));
        receipt.put("source_read_only", true).put("target_disposable", true);
        JSON.writeValue(output.resolve("java-receipt.json").toFile(), receipt);
        assertEquals(827791, exported.path("catalog_rows").asInt());
        assertEquals(93184, binding.sampleListeners());
    }
}
