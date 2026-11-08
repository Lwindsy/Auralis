package io.auralis.catalog.api;

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
}
