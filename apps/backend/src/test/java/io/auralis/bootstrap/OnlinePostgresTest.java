package io.auralis.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Only an explicitly supplied disposable PostgreSQL 18 instance; never the real core database. */
@EnabledIfEnvironmentVariable(named = "AURALIS_TEST_POSTGRES_URL", matches = ".+")
class OnlinePostgresTest {
    static String url;
    static final String PASSWORD = "be004-synthetic-runtime";

    @BeforeAll
    static void database() throws Exception {
        url = System.getenv("AURALIS_TEST_POSTGRES_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core_verify"));
        try (var c =
                        DriverManager.getConnection(
                                url,
                                "postgres",
                                System.getenv("AURALIS_TEST_POSTGRES_ADMIN_PASSWORD"));
                var s = c.createStatement()) {
            s.execute(
                    "DO $$ BEGIN CREATE ROLE be004_migrator LOGIN PASSWORD 'be004-synthetic-runtime'; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
            s.execute(
                    "DO $$ BEGIN CREATE ROLE auralis_online LOGIN PASSWORD 'be004-synthetic-runtime'; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
            s.execute("GRANT CREATE ON DATABASE auralis_core_verify TO be004_migrator");
            s.execute("GRANT USAGE, CREATE ON SCHEMA public TO be004_migrator");
        }
        Flyway.configure()
                .dataSource(url, "be004_migrator", PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (var c = DriverManager.getConnection(url, "be004_migrator", PASSWORD);
                var s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS public.bootstrap_probe (id bigint primary key, value varchar(255))");
            s.execute("TRUNCATE public.bootstrap_probe");
            s.execute("GRANT SELECT ON public.flyway_schema_history TO auralis_online");
            s.execute("GRANT USAGE ON SCHEMA discovery TO auralis_online");
            s.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA discovery TO auralis_online");
            s.execute("GRANT SELECT, INSERT, DELETE ON public.bootstrap_probe TO auralis_online");
        }
    }

    private ServletWebServerApplicationContext start(Map<String, Object> overrides) {
        var properties = new java.util.HashMap<String, Object>();
        properties.put("server.port", 0);
        properties.put("auralis.jdbc-url", url);
        properties.put("auralis.database-user", "auralis_online");
        properties.put("auralis.database-password", PASSWORD);
        properties.put("auralis.product-origin", "http://127.0.0.1:8080");
        properties.put("auralis.python-url", "http://127.0.0.1:1");
        properties.put("auralis.service-token", OnlineBootstrapTest.TOKEN);
        properties.put("auralis.contract-revision", "0.30");
        properties.put(
                "auralis.subject-secret",
                "test-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
        properties.put("auralis.allow-insecure-cookie", true);
        properties.put("auralis.session-cleanup-enabled", false);
        properties.putAll(overrides);
        var application = new SpringApplication(OnlineApplication.class);
        application.setDefaultProperties(properties);
        // Command-line values take precedence over packaged defaults such as server.port.
        return (ServletWebServerApplicationContext) application.run("--server.port=0");
    }

    @Test
    void startsServesRealHttpAndSharesJpaJdbcCommitAndRollback() throws Exception {
        HikariDataSource pool;
        try (var context = start(Map.of());
                var client = HttpClient.newHttpClient()) {
            pool = context.getBean(HikariDataSource.class);
            var manager = context.getBean(JpaTransactionManager.class);
            var factory = context.getBean(EntityManagerFactory.class);
            assertSame(pool, manager.getDataSource());
            assertSame(pool, context.getBean(DataSource.class));
            var jdbc = context.getBean(JdbcTemplate.class);
            var tx = new TransactionTemplate(manager);
            tx.executeWithoutResult(
                    status -> {
                        var entity = new Probe();
                        entity.id = 1L;
                        entity.value = "committed";
                        var em = EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
                        em.persist(entity);
                        em.flush();
                        jdbc.update("INSERT INTO bootstrap_probe VALUES (2, 'jdbc')");
                    });
            assertEquals(
                    2, jdbc.queryForObject("SELECT count(*) FROM bootstrap_probe", Integer.class));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            tx.executeWithoutResult(
                                    status -> {
                                        var entity = new Probe();
                                        entity.id = 3L;
                                        entity.value = "rolled-back";
                                        var em =
                                                EntityManagerFactoryUtils
                                                        .getTransactionalEntityManager(factory);
                                        em.persist(entity);
                                        em.flush();
                                        jdbc.update(
                                                "INSERT INTO bootstrap_probe VALUES (4, 'rollback-jdbc')");
                                        throw new IllegalStateException("rollback");
                                    }));
            assertEquals(
                    2, jdbc.queryForObject("SELECT count(*) FROM bootstrap_probe", Integer.class));
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            assertEquals(200, get(client, base + "/actuator/health/liveness").statusCode());
            assertEquals(503, get(client, base + "/actuator/health/readiness").statusCode());
            var denied = get(client, base + "/internal/v1/runtime");
            assertEquals(401, denied.statusCode());
            assertTrue(
                    context.getBean(WireProtocol.class)
                            .valid(OnlineBootstrapTest.JSON.readTree(denied.body())));
            var origin =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/v1/bootstrap"))
                                    .header("Origin", "https://foreign.invalid")
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, origin.statusCode());
            assertTrue(
                    context.getBean(WireProtocol.class)
                            .validProductError(OnlineBootstrapTest.JSON.readTree(origin.body())));
            var authorizedMissing =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/internal/v1/runtime"))
                                    .header("Authorization", "Bearer " + OnlineBootstrapTest.TOKEN)
                                    .header("X-Auralis-Audience", "coordinator")
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(404, authorizedMissing.statusCode());
            assertTrue(
                    context.getBean(WireProtocol.class)
                            .valid(OnlineBootstrapTest.JSON.readTree(authorizedMissing.body())));
            assertFalse(origin.body().contains(PASSWORD));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> jdbc.execute("CREATE TABLE should_not_exist(id int)"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> jdbc.execute("CREATE TABLE discovery.should_not_exist(id int)"));
        }
        assertTrue(pool.isClosed());
    }

    @Test
    void rejectsWrongCredentialsAndChangedMigrationChecksum() throws Exception {
        assertThrows(
                Exception.class,
                () -> {
                    try (var ignored = start(Map.of("auralis.database-password", "wrong-secret"))) {
                        fail();
                    }
                });
        try (var c = DriverManager.getConnection(url, "be004_migrator", PASSWORD);
                var s = c.createStatement()) {
            var result =
                    s.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='6'");
            assertTrue(result.next());
            int checksum = result.getInt(1);
            result.close();
            s.executeUpdate("UPDATE flyway_schema_history SET checksum=1 WHERE version='6'");
            try {
                assertThrows(
                        Exception.class,
                        () -> {
                            try (var ignored = start(Map.of())) {
                                fail();
                            }
                        });
            } finally {
                s.executeUpdate(
                        "UPDATE flyway_schema_history SET checksum="
                                + checksum
                                + " WHERE version='6'");
            }
        }
    }

    private HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(uri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Entity(name = "BootstrapProbe")
    @Table(name = "bootstrap_probe")
    public static class Probe {
        @Id Long id;
        String value;

        public Probe() {}
    }
}
