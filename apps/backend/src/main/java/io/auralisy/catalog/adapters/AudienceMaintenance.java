package io.auralisy.catalog.adapters;

import static io.auralisy.catalog.application.CatalogJson.JSON;

import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Environment credentials only; source and target must be explicitly supplied. */
public final class AudienceMaintenance {
    private AudienceMaintenance() {}

    public static void main(String[] args) {
        try {
            if (args.length < 5) throw new IllegalArgumentException("USAGE");
            var source = dataSource("AURALIS_AUDIENCE_SOURCE");
            var sourceJdbc = new JdbcTemplate(source);
            sourceJdbc.setFetchSize(1000);
            long dataset = Long.parseLong(args[1]);
            if (args[0].equals("export") && args.length == 5) {
                var importer = new FullAudienceImport(sourceJdbc, null, null);
                System.out.println(
                        JSON.writeValueAsString(
                                importer.export(
                                        dataset,
                                        Integer.parseInt(args[2]),
                                        args[3],
                                        Path.of(args[4]))));
            } else if (args[0].equals("admit") && args.length == 6) {
                var target = dataSource("AURALIS_AUDIENCE_TARGET");
                var tx = new TransactionTemplate(new DataSourceTransactionManager(target));
                tx.setTimeout(600);
                var importer = new FullAudienceImport(sourceJdbc, new JdbcTemplate(target), tx);
                System.out.println(
                        JSON.writeValueAsString(
                                importer.admit(
                                        Path.of(args[4]), args[5], dataset, args[3], args[2])));
            } else throw new IllegalArgumentException("USAGE");
        } catch (Exception e) {
            System.err.println("AUDIENCE_MAINTENANCE_FAILED");
            System.exit(1);
        }
    }

    private static DriverManagerDataSource dataSource(String prefix) {
        String url = System.getenv(prefix + "_URL"),
                user = System.getenv(prefix + "_USER"),
                password = System.getenv(prefix + "_PASSWORD");
        if (url == null
                || !url.matches("jdbc:postgresql://[^/?#]+/[a-zA-Z0-9_]+")
                || user == null
                || password == null) throw new IllegalArgumentException("CONFIG_REQUIRED");
        return new DriverManagerDataSource(url, user, password);
    }
}
