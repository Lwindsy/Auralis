package io.auralisy.catalog;

import static io.auralisy.catalog.application.CatalogJson.JSON;

import io.auralisy.catalog.adapters.GenreTaxonomy;
import java.nio.file.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit synthetic taxonomy for legacy database fixtures; never used by the application. */
public final class TaxonomyFixtures {
    public static Path write(JdbcTemplate jdbc) {
        try {
            var document =
                    JSON.createObjectNode()
                            .put("format_version", 1)
                            .put("source_sha256", "0".repeat(64))
                            .put(
                                    "core_manifest_sha256",
                                    jdbc.queryForObject(
                                            "SELECT d.manifest_sha256 FROM ingestion.dataset d JOIN ingestion.active_dataset a ON a.dataset_id=d.id",
                                            String.class));
            var rows = document.putArray("genres");
            jdbc.query(
                    "SELECT mbid::text,name,id FROM catalog.current_genre ORDER BY id",
                    rs -> {
                        var row =
                                rows.addObject()
                                        .put("id", rs.getString(1))
                                        .put("name", rs.getString(2));
                        var aliases = row.putArray("aliases");
                        jdbc.query(
                                "SELECT name FROM catalog.current_genre_alias WHERE genre_id=?",
                                a -> {
                                    aliases.add(a.getString(1));
                                },
                                rs.getInt(3));
                        var parents = row.putArray("parents");
                        jdbc.query(
                                "SELECT g.mbid::text FROM catalog.current_genre_relation r JOIN catalog.current_genre g ON g.id=r.source_genre WHERE r.target_genre=? AND r.relation_type='subgenre'",
                                p -> {
                                    parents.add(p.getString(1));
                                },
                                rs.getInt(3));
                    });
            Path path = Files.createTempFile("synthetic-taxonomy-", ".json");
            Files.writeString(path, document.toString());
            path.toFile().deleteOnExit();
            return path;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static GenreTaxonomy load(JdbcTemplate jdbc) throws Exception {
        return new GenreTaxonomy(write(jdbc).toString());
    }
}
