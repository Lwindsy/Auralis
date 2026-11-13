package io.auralisy.catalog.adapters;

import static io.auralisy.catalog.application.CatalogJson.JSON;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.auralisy.catalog.api.GenreQuery;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One explicitly configured RYM graph; raw MusicBrainz relations are not product authority. */
@Component
public final class GenreTaxonomy {
    private final Map<String, GenreQuery.Node> nodes;
    private final String manifest;

    @org.springframework.beans.factory.annotation.Autowired
    public GenreTaxonomy(@Value("${AURALIS_GENRE_TAXONOMY:}") String path)
            throws java.io.IOException {
        this(path.isBlank() ? null : JSON.readTree(Files.readAllBytes(Path.of(path))));
    }

    public GenreTaxonomy(JsonNode document) throws java.io.IOException {
        if (document == null) {
            manifest = null;
            nodes = Map.of();
            return;
        }
        try (var input = getClass().getResourceAsStream("/genre-taxonomy-v1.schema.json")) {
            var schema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(JSON.readTree(input));
            if (!schema.validate(document).isEmpty())
                throw new IllegalArgumentException("INVALID_GENRE_TAXONOMY");
        }
        manifest = document.path("core_manifest_sha256").asText();
        var children = new HashSet<String>();
        for (var row : document.path("genres"))
            for (var parent : row.path("parents")) children.add(parent.asText());
        var values = new LinkedHashMap<String, GenreQuery.Node>();
        for (var row : document.path("genres")) {
            String id = row.path("id").asText();
            var aliases = new HashSet<String>();
            var parents = new HashSet<String>();
            row.path("aliases").forEach(a -> aliases.add(a.asText()));
            row.path("parents").forEach(p -> parents.add(p.asText()));
            if (values.putIfAbsent(
                            id,
                            new GenreQuery.Node(
                                    id,
                                    row.path("name").asText(),
                                    aliases,
                                    parents,
                                    children.contains(id),
                                    null))
                    != null) throw new IllegalArgumentException("DUPLICATE_TAXONOMY_ID");
        }
        var visited = new HashSet<String>();
        for (String id : values.keySet()) visit(id, values, visited, new HashSet<>());
        nodes = Collections.unmodifiableMap(values);
    }

    private static void visit(
            String id,
            Map<String, GenreQuery.Node> nodes,
            Set<String> visited,
            Set<String> active) {
        if (!nodes.containsKey(id)) throw new IllegalArgumentException("TAXONOMY_PARENT_MISSING");
        if (active.contains(id)) throw new IllegalArgumentException("TAXONOMY_CYCLE");
        if (visited.contains(id)) return;
        active.add(id);
        for (String parent : nodes.get(id).parents()) visit(parent, nodes, visited, active);
        active.remove(id);
        visited.add(id);
    }

    public String manifest() {
        if (manifest == null) throw new IllegalStateException("GENRE_TAXONOMY_REQUIRED");
        return manifest;
    }

    public Map<String, GenreQuery.Node> nodes() {
        manifest();
        return nodes;
    }
}
