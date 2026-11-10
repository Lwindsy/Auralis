package io.auralis.catalog.adapters;

import io.auralis.catalog.api.GenreQuery;
import java.util.*;
import org.springframework.stereotype.Repository;

/** One immutable current-version cache; maintenance can warm it before accepting requests. */
@Repository
public class PostgresGenreQuery implements GenreQuery {
    private final PostgresCatalogQuery catalog;
    private final GenreTaxonomy taxonomy;

    public PostgresGenreQuery(PostgresCatalogQuery catalog, GenreTaxonomy taxonomy) {
        this.catalog = catalog;
        this.taxonomy = taxonomy;
    }

    public Map<String, Node> warm(long dataset) {
        catalog.requireActive(dataset);
        return taxonomy.nodes();
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
        return closure(nodes, genres);
    }

    private static Set<String> closure(Map<String, Node> nodes, Set<String> genres) {
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

    @Override
    public Map<String, Set<String>> descendantGroups(long dataset, Set<String> roots) {
        var nodes = warm(dataset);
        var groups = new LinkedHashMap<String, Set<String>>();
        for (String root : roots) groups.put(root, closure(nodes, Set.of(root)));
        return Collections.unmodifiableMap(groups);
    }

    @Override
    public Map<String, Node> nodes(long dataset, Set<String> genres) {
        if (genres.size() > 14000) throw new IllegalArgumentException("BATCH_TOO_LARGE");
        var all = warm(dataset);
        var result = new LinkedHashMap<String, Node>();
        for (String id : genres) if (all.containsKey(id)) result.put(id, all.get(id));
        return Collections.unmodifiableMap(result);
    }
}
