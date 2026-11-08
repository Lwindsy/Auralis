package io.auralis.catalog.adapters;

import io.auralis.catalog.api.GenreQuery;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One immutable current-version cache; maintenance can warm it before accepting requests. */
@Repository
public class PostgresGenreQuery implements GenreQuery {
    private final JdbcTemplate jdbc;
    private final PostgresCatalogQuery catalog;
    private long version = -1;
    private Map<String, Node> cached = Map.of();

    public PostgresGenreQuery(JdbcTemplate jdbc, PostgresCatalogQuery catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public synchronized Map<String, Node> warm(long dataset) {
        catalog.requireActive(dataset);
        if (version == dataset) return cached;
        var names = new LinkedHashMap<String, String>();
        var aliases = new HashMap<String, Set<String>>();
        var parents = new HashMap<String, Set<String>>();
        var hasChildren = new HashSet<String>();
        var annotations = new HashMap<String, String>();
        jdbc.query(
                "SELECT mbid::text,name FROM catalog.current_genre WHERE dataset_id=? ORDER BY mbid",
                rs -> {
                    names.put(rs.getString(1), rs.getString(2));
                },
                dataset);
        jdbc.query(
                "SELECT g.mbid::text,a.name FROM catalog.current_genre_alias a JOIN catalog.current_genre g ON g.dataset_id=a.dataset_id AND g.id=a.genre_id WHERE a.dataset_id=?",
                rs -> {
                    aliases.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                            .add(rs.getString(2));
                },
                dataset);
        jdbc.query(
                "SELECT child.mbid::text,parent.mbid::text FROM catalog.current_genre_relation r JOIN catalog.current_genre child ON child.dataset_id=r.dataset_id AND child.id=r.target_genre JOIN catalog.current_genre parent ON parent.dataset_id=r.dataset_id AND parent.id=r.source_genre WHERE r.dataset_id=? AND r.relation_type='subgenre'",
                rs -> {
                    parents.computeIfAbsent(rs.getString(1), k -> new HashSet<>())
                            .add(rs.getString(2));
                    hasChildren.add(rs.getString(2));
                },
                dataset);
        jdbc.query(
                "SELECT genre_mbid::text,annotation::text FROM catalog.current_genre_hierarchy_annotation WHERE dataset_id=?",
                rs -> {
                    annotations.put(rs.getString(1), rs.getString(2));
                },
                dataset);
        var result = new LinkedHashMap<String, Node>();
        names.forEach(
                (id, name) ->
                        result.put(
                                id,
                                new Node(
                                        id,
                                        name,
                                        aliases.getOrDefault(id, Set.of()),
                                        parents.getOrDefault(id, Set.of()),
                                        hasChildren.contains(id),
                                        annotations.get(id))));
        catalog.requireActive(dataset);
        cached = Collections.unmodifiableMap(result);
        version = dataset;
        return cached;
    }

    @Override
    public List<Node> search(long dataset, String query, String parent, int offset, int limit) {
        if (offset < 0
                || offset > 10000
                || limit < 1
                || limit > 50
                || (query != null && query.codePointCount(0, query.length()) > 200))
            throw new IllegalArgumentException("INVALID_QUERY");
        var nodes = warm(dataset);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return nodes.values().stream()
                .filter(
                        n ->
                                q.isEmpty()
                                        ? (parent == null
                                                ? n.parents().isEmpty()
                                                : n.parents().contains(parent))
                                        : n.name().toLowerCase(Locale.ROOT).contains(q)
                                                || n.aliases().stream()
                                                        .anyMatch(
                                                                a ->
                                                                        a.toLowerCase(Locale.ROOT)
                                                                                .contains(q)))
                .sorted(Comparator.comparing(Node::name).thenComparing(Node::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public Set<String> descendants(long dataset, Set<String> genres) {
        var nodes = warm(dataset);
        if (!nodes.keySet().containsAll(genres))
            throw new IllegalArgumentException("UNKNOWN_GENRE");
        var result = new HashSet<>(genres);
        boolean changed;
        do {
            changed = false;
            for (var node : nodes.values())
                if (!Collections.disjoint(node.parents(), result)) changed |= result.add(node.id());
        } while (changed);
        return Set.copyOf(result);
    }
}
