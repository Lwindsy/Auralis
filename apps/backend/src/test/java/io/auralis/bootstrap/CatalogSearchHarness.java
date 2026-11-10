package io.auralis.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.auralis.catalog.adapters.*;
import io.auralis.catalog.api.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Test-only HTTP host: real application, disposable identity DB, optional read-only real catalog.
 */
public final class CatalogSearchHarness implements AutoCloseable {
    public final ServletWebServerApplicationContext context;
    public final String base;

    public CatalogSearchHarness(Path index, String manifest) throws Exception {
        String url = System.getenv("AURALIS_SEARCH_TEST_POSTGRES_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        String password = System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD");
        Flyway.configure()
                .dataSource(url, "postgres", password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (var c = DriverManager.getConnection(url, "postgres", password);
                var s = c.createStatement()) {
            s.execute("CREATE ROLE be012_online LOGIN PASSWORD 'be012-synthetic-runtime'");
            s.execute(
                    "CREATE TABLE public.bootstrap_probe (id bigint primary key,value varchar(255))");
            s.execute("GRANT SELECT,INSERT,DELETE ON public.bootstrap_probe TO be012_online");
            s.execute(
                    "GRANT USAGE ON SCHEMA ingestion,catalog,statistics,discovery TO be012_online");
            s.execute(
                    "GRANT SELECT ON ALL TABLES IN SCHEMA ingestion,catalog,statistics TO be012_online");
            s.execute(
                    "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO be012_online");
            s.execute("GRANT SELECT ON public.flyway_schema_history TO be012_online");
            s.execute(
                    "INSERT INTO ingestion.dataset(manifest_sha256,layout_sha256,purpose,manifest,status) VALUES(repeat('a',64),repeat('b',64),'private-development','{}','IMPORTING')");
            s.execute(
                    "INSERT INTO catalog.genre VALUES(1,1,'33333333-3333-4333-8333-333333333333','Rock','')");
            s.execute("INSERT INTO catalog.genre_alias VALUES(1,1,1,'摇滚','摇滚','zh',NULL,true)");
            s.execute("UPDATE ingestion.dataset SET status='ACTIVE' WHERE id=1");
            s.execute("INSERT INTO ingestion.active_dataset VALUES(true,1)");
        }
        var app = new SpringApplication(OnlineApplication.class, Pages.class, RealCatalog.class);
        app.setAdditionalProfiles(
                System.getenv("AURALIS_SEARCH_REAL_CORE_CONFIG") == null
                        ? "be012-http"
                        : "be012-http,be012-real");
        if (System.getenv("AURALIS_SEARCH_REAL_CORE_CONFIG") != null)
            app.setAdditionalProfiles("be012-http", "be012-real");
        var p = new HashMap<String, Object>();
        p.put("auralis.jdbc-url", url);
        p.put("auralis.database-user", "be012_online");
        p.put("auralis.database-password", "be012-synthetic-runtime");
        p.put("auralis.product-origin", "http://127.0.0.1:8080");
        p.put("auralis.python-url", "http://127.0.0.1:1");
        p.put("auralis.service-token", "be012-synthetic-service-token-123456789");
        p.put(
                "auralis.subject-secret",
                "be012-synthetic-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
        p.put("auralis.contract-revision", new WireProtocol(new ObjectMapper()).revision());
        p.put("auralis.allow-insecure-cookie", true);
        p.put("auralis.session-cleanup-enabled", false);
        p.put("AURALIS_CATALOG_SEARCH_INDEX", index.toString());
        p.put("AURALIS_CATALOG_SEARCH_MANIFEST_SHA256", manifest);
        p.put(
                "AURALIS_GENRE_TAXONOMY",
                System.getenv()
                        .getOrDefault(
                                "AURALIS_GENRE_TAXONOMY",
                                io.auralis.catalog.TaxonomyFixtures.write(
                                                new JdbcTemplate(
                                                        new org.springframework.jdbc.datasource
                                                                .DriverManagerDataSource(
                                                                url, "postgres", password)))
                                        .toString()));
        app.setDefaultProperties(p);
        context = (ServletWebServerApplicationContext) app.run("--server.port=0");
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
        context.getBean(OnlineProperties.class)
                .setProductOrigin(
                        System.getenv().getOrDefault("AURALIS_SEARCH_BROWSER_ORIGIN", base));
    }

    public void close() {
        context.close();
    }

    public static void main(String[] args) throws Exception {
        try (var host = new CatalogSearchHarness(Path.of(args[0]), args[1])) {
            Files.writeString(Path.of(args[2]), host.base);
            System.out.println("CATALOG_HARNESS_READY " + host.base);
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }

    @RestController
    @Profile("be012-http")
    public static class Pages {
        @GetMapping(value = "/v1/catalog/benchmark", produces = "text/html;charset=UTF-8")
        String page() {
            return "<!doctype html><html lang='zh'><meta charset='utf-8'><title>目录搜索计时</title><body><h1>目录搜索验证</h1><ul id='results'></ul></body></html>";
        }
    }

    @Configuration
    @Profile("be012-real")
    public static class RealCatalog {
        private HikariDataSource source;

        synchronized JdbcTemplate source() throws Exception {
            if (source == null) {
                var cfg =
                        new ObjectMapper()
                                .readTree(
                                        Path.of(System.getenv("AURALIS_SEARCH_REAL_CORE_CONFIG"))
                                                .toFile());
                source = new HikariDataSource();
                source.setJdbcUrl(cfg.path("jdbc_url").asText());
                source.setUsername(cfg.path("importer_user").asText());
                source.setPassword(cfg.path("importer_password").asText());
                source.setReadOnly(true);
                source.setMaximumPoolSize(5);
                source.setMinimumIdle(1);
                source.setConnectionTimeout(1000);
                source.addDataSourceProperty(
                        "options", "-c default_transaction_read_only=on -c statement_timeout=1000");
            }
            return new JdbcTemplate(source);
        }

        @Bean
        @Primary
        PostgresCatalogQuery realCatalogQuery() throws Exception {
            return new PostgresCatalogQuery(
                    source(), new GenreTaxonomy(System.getenv("AURALIS_GENRE_TAXONOMY")));
        }

        @Bean
        @Primary
        PostgresGenreQuery realGenreQuery() throws Exception {
            return new PostgresGenreQuery(
                    realCatalogQuery(), new GenreTaxonomy(System.getenv("AURALIS_GENRE_TAXONOMY")));
        }

        @Bean
        @Primary
        CatalogVersion realCatalogVersion() throws Exception {
            return new PostgresCatalogVersion(source());
        }

        @jakarta.annotation.PreDestroy
        void close() {
            if (source != null) source.close();
        }
    }
}
