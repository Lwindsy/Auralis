package io.auralis.catalog.adapters;

import io.auralis.catalog.api.CatalogVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresCatalogVersion implements CatalogVersion {
    private final JdbcTemplate jdbc;

    public PostgresCatalogVersion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long current() {
        return jdbc.queryForObject("SELECT dataset_id FROM ingestion.active_dataset", Long.class);
    }
}
