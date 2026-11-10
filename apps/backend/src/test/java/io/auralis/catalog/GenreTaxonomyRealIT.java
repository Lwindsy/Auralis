package io.auralis.catalog;

import static io.auralis.catalog.application.CatalogJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

import io.auralis.catalog.adapters.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real core source is read-only; no online recommendation or provider calls. */
@EnabledIfSystemProperty(named = "auralis.test.core-config", matches = ".+")
class GenreTaxonomyRealIT {
    @Test
    void realCatalogAndSourceGraphAgreeWithoutRawLabelLeakage() throws Exception {
        var config =
                JSON.readTree(Path.of(System.getProperty("auralis.test.core-config")).toFile());
        var source =
                new DriverManagerDataSource(
                        config.path("jdbc_url").asText(),
                        config.path("importer_user").asText(),
                        config.path("importer_password").asText());
        var properties = new Properties();
        properties.setProperty(
                "options", "-c default_transaction_read_only=on -c statement_timeout=30000");
        source.setConnectionProperties(properties);
        var jdbc = new JdbcTemplate(source);
        assertEquals("on", jdbc.queryForObject("SHOW transaction_read_only", String.class));
        long dataset =
                jdbc.queryForObject("SELECT dataset_id FROM ingestion.active_dataset", Long.class);
        var taxonomy = new GenreTaxonomy(System.getProperty("auralis.test.taxonomy"));
        var catalog = new PostgresCatalogQuery(jdbc, taxonomy);
        var genres = new PostgresGenreQuery(catalog, taxonomy);
        assertEquals(taxonomy.nodes(), genres.nodes(dataset, taxonomy.nodes().keySet()));
        var raw = jdbc.queryForList("SELECT mbid::text FROM catalog.current_genre", String.class);
        var removed = new HashSet<>(raw);
        removed.removeAll(taxonomy.nodes().keySet());
        assertFalse(removed.isEmpty());
        assertTrue(genres.nodes(dataset, removed).isEmpty());
        // Select real albums that carried a removed tag, including multi-tag works.
        var ids =
                jdbc.query(
                        "SELECT DISTINCT a.mbid FROM catalog.current_album a JOIN catalog.current_album_genre ag ON ag.album_id=a.id JOIN catalog.current_genre g ON g.id=ag.genre_id WHERE g.mbid::text=ANY(?) LIMIT 100",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        (Object) removed.toArray(String[]::new));
        assertFalse(ids.isEmpty());
        var albums = catalog.albums(dataset, ids);
        assertEquals(ids.size(), albums.size());
        for (var album : albums.values())
            for (var genre : album.genres()) {
                assertTrue(taxonomy.nodes().containsKey(genre.genreId()));
                assertEquals(taxonomy.nodes().get(genre.genreId()).name(), genre.name());
            }
        System.out.println(
                "TAXONOMY_REAL_CHECK nodes="
                        + taxonomy.nodes().size()
                        + " removed="
                        + removed.size()
                        + " albums="
                        + albums.size());
    }
}
