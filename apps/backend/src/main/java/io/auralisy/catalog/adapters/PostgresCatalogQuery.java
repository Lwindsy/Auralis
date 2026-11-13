package io.auralisy.catalog.adapters;

import io.auralisy.catalog.api.CatalogQuery;
import io.auralisy.persistence.PersistenceRejected;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresCatalogQuery implements CatalogQuery {
    private final JdbcTemplate jdbc;
    private final GenreTaxonomy taxonomy;

    public PostgresCatalogQuery(JdbcTemplate jdbc, GenreTaxonomy taxonomy) {
        this.jdbc = jdbc;
        this.taxonomy = taxonomy;
    }

    public void requireActive(long dataset) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM ingestion.active_dataset WHERE dataset_id=?)",
                        Boolean.class,
                        dataset))) throw new PersistenceRejected("CATALOG_VERSION_MISMATCH");
        if (!taxonomy.manifest()
                .equals(
                        jdbc.queryForObject(
                                "SELECT manifest_sha256 FROM ingestion.dataset WHERE id=?",
                                String.class,
                                dataset)))
            throw new PersistenceRejected("TAXONOMY_CATALOG_MISMATCH");
    }

    @Override
    public Map<UUID, Album> albums(long dataset, List<UUID> requested) {
        if (requested.size() > 1000) throw new IllegalArgumentException("BATCH_TOO_LARGE");
        requireActive(dataset);
        if (requested.isEmpty()) return Map.of();
        // One query, including redirects and ordered credit. Lateral indexed lookups are
        // database joins, not per-candidate JDBC calls. No popularity/detail table reads.
        String values = String.join(",", Collections.nCopies(requested.size(), "(?::uuid)"));
        var args = new ArrayList<Object>(requested);
        args.add(dataset);
        var result = new LinkedHashMap<UUID, Album>();
        jdbc.query(
                "WITH requested(id) AS (VALUES "
                        + values
                        + ") "
                        + "SELECT r.id,a.mbid,a.title,a.first_year,a.primary_type,credits.name,types.names,genres.items,(direct.id IS NOT NULL AND red.album_id IS NOT NULL AND direct.id<>red.album_id) "
                        + "FROM requested r LEFT JOIN catalog.current_album_redirect red ON red.old_mbid=r.id "
                        + "LEFT JOIN catalog.current_album direct ON direct.mbid=r.id "
                        + "JOIN catalog.current_album a ON a.id=coalesce(direct.id,red.album_id) "
                        + "LEFT JOIN LATERAL (SELECT string_agg(c.credited_name||c.join_phrase,'' ORDER BY c.position) name FROM catalog.current_album_credit c WHERE c.dataset_id=a.dataset_id AND c.album_id=a.id) credits ON true "
                        + "LEFT JOIN LATERAL (SELECT array_agg(t.name ORDER BY t.name) names FROM catalog.current_album_secondary_type t WHERE t.dataset_id=a.dataset_id AND t.album_id=a.id) types ON true "
                        + "LEFT JOIN LATERAL (SELECT jsonb_agg(x.item ORDER BY x.gid) items FROM (SELECT DISTINCT g.mbid::text gid,jsonb_build_object('genre_id',g.mbid::text,'name',g.name) item FROM catalog.current_album_genre ag JOIN catalog.current_genre g ON g.dataset_id=ag.dataset_id AND g.id=ag.genre_id WHERE ag.dataset_id=a.dataset_id AND ag.album_id=a.id) x) genres ON true "
                        + "WHERE a.dataset_id=?",
                rs -> {
                    if (rs.getBoolean(9)) throw new PersistenceRejected("AMBIGUOUS_REDIRECT");
                    UUID requestedId = rs.getObject(1, UUID.class);
                    var genreList = new ArrayList<Genre>();
                    try {
                        var text = rs.getString(8);
                        if (text != null)
                            for (var g :
                                    io.auralisy.catalog.application.CatalogJson.JSON.readTree(text))
                                if (taxonomy.nodes().containsKey(g.path("genre_id").asText()))
                                    genreList.add(
                                            new Genre(
                                                    g.path("genre_id").asText(),
                                                    taxonomy.nodes()
                                                            .get(g.path("genre_id").asText())
                                                            .name()));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("INVALID_CATALOG_GENRES", e);
                    }
                    var typeArray = rs.getArray(7);
                    var album =
                            new Album(
                                    rs.getObject(2, UUID.class),
                                    rs.getString(3),
                                    rs.getString(6),
                                    rs.getObject(4, Integer.class),
                                    rs.getString(5),
                                    typeArray == null
                                            ? List.of()
                                            : List.of((String[]) typeArray.getArray()),
                                    genreList);
                    if (result.putIfAbsent(requestedId, album) != null
                            && !result.get(requestedId).equals(album))
                        throw new PersistenceRejected("AMBIGUOUS_REDIRECT");
                },
                args.toArray());
        requireActive(dataset);
        return Collections.unmodifiableMap(result);
    }
}
