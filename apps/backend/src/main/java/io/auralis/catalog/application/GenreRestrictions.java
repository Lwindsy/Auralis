package io.auralis.catalog.application;

import java.util.*;

/** Matches recall.common.effective_disliked; hard forbidden genres are deliberately separate. */
public final class GenreRestrictions {
    private GenreRestrictions() {}

    public static Set<String> disliked(
            Set<String> roots, Set<String> explicitPositive, Map<String, Set<String>> closures) {
        var result = new HashSet<String>();
        for (String root : roots) {
            var blocked = new HashSet<>(closures.get(root));
            for (String exception : explicitPositive)
                if (!exception.equals(root) && closures.get(root).contains(exception))
                    blocked.removeAll(closures.get(exception));
            result.addAll(blocked);
        }
        return Set.copyOf(result);
    }
}
