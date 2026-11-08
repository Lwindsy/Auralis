package io.auralis.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(OnlineProperties properties, WireProtocol protocol) {
        properties.validate(protocol.revision());
        var config = new HikariConfig();
        config.setJdbcUrl(properties.getJdbcUrl());
        config.setUsername(properties.getDatabaseUser());
        config.setPassword(properties.getDatabasePassword());
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(2000);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(2000);
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", "5");
        config.setPoolName("auralis-online");
        var pool = new HikariDataSource(config);
        try (var connection = pool.getConnection();
                var statement = connection.createStatement();
                var row =
                        statement.executeQuery(
                                "SELECT current_setting('server_version_num')::int, "
                                        + "rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls "
                                        + "OR has_database_privilege(current_user,current_database(),'CREATE') "
                                        + "OR has_schema_privilege(current_user,'public','CREATE') "
                                        + "FROM pg_roles WHERE rolname=current_user")) {
            if (!row.next() || row.getInt(1) / 10000 != 18 || row.getBoolean(2))
                throw new IllegalStateException("DATABASE_IDENTITY_REJECTED");
            return pool;
        } catch (Exception failure) {
            pool.close();
            throw new IllegalStateException("DATABASE_UNAVAILABLE_OR_IDENTITY_REJECTED");
        }
    }

    @Bean
    Flyway flyway(DataSource dataSource) {
        try {
            var flyway =
                    Flyway.configure()
                            .dataSource(dataSource)
                            .locations("classpath:db/migration")
                            .cleanDisabled(true)
                            .ignoreMigrationPatterns(new String[0])
                            .validateMigrationNaming(true)
                            .load();
            flyway.validate();
            if (flyway.info().pending().length != 0)
                throw new IllegalStateException("MIGRATION_MISMATCH");
            return flyway;
        } catch (Exception failure) {
            throw new IllegalStateException("MIGRATION_MISMATCH");
        }
    }
}
