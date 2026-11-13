package io.auralisy.bootstrap;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;

/** Explicit offline migration, separate credentials; never part of normal online boot. */
final class OfflineMigration {
    static void run() throws Exception {
        String url = System.getenv("AURALIS_JDBC_URL");
        String user = System.getenv("AURALIS_MIGRATOR_USER");
        String password = System.getenv("AURALIS_MIGRATOR_PASSWORD");
        if (url == null
                || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/auralis_core(?:_verify)?")
                || !"auralis_migrator".equals(user)
                || password == null
                || password.isBlank()) throw new IllegalArgumentException();
        try (var connection = DriverManager.getConnection(url, user, password);
                var query = connection.createStatement();
                var row =
                        query.executeQuery(
                                "SELECT current_setting('server_version_num')::int, rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            if (!row.next() || row.getInt(1) / 10000 != 18 || row.getBoolean(2))
                throw new IllegalArgumentException();
        }
        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .ignoreMigrationPatterns(new String[0])
                .load()
                .migrate();
        System.out.println("AURALIS_MIGRATED");
    }
}
