package io.auralisy.catalog.adapters;

import io.auralisy.catalog.api.CatalogFailure;
import io.auralisy.catalog.api.CatalogVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresCatalogVersion implements CatalogVersion {
    private final JdbcTemplate jdbc;

    public PostgresCatalogVersion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long current() {
        var rows = jdbc.queryForList("SELECT dataset_id FROM ingestion.active_dataset", Long.class);
        if (rows.size() != 1) throw new CatalogFailure(503, "CATALOG_UNAVAILABLE");
        return rows.getFirst();
    }
}
