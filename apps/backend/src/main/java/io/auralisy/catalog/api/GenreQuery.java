package io.auralisy.catalog.api;

import java.util.*;

public interface GenreQuery {
    record Node(
            String id,
            String name,
            Set<String> aliases,
            Set<String> parents,
            boolean hasChildren,
            String annotation) {
        public Node {
            aliases = Set.copyOf(aliases);
            parents = Set.copyOf(parents);
        }
    }

    List<Node> search(long dataset, String query, String parent, int offset, int limit);

    Set<String> descendants(long dataset, Set<String> genres);

    /** Batch lookup in the same versioned cache; absent IDs remain absent. */
    Map<String, Node> nodes(long dataset, Set<String> genres);

    /** Inclusive descendant closure per root, from a single pinned cache lookup. */
    Map<String, Set<String>> descendantGroups(long dataset, Set<String> roots);
}
