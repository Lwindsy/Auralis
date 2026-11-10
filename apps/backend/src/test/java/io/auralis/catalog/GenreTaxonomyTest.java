package io.auralis.catalog;

import static io.auralis.catalog.application.CatalogJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

import io.auralis.catalog.adapters.GenreTaxonomy;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GenreTaxonomyTest {
    @Test
    void preservesMultipleParentsAndRejectsInvalidGraph() throws Exception {
        var d =
                JSON.createObjectNode()
                        .put("format_version", 1)
                        .put("source_sha256", "a".repeat(64))
                        .put("core_manifest_sha256", "b".repeat(64));
        var rows = d.putArray("genres");
        for (String id : new String[] {"rock", "jazz", "fusion"}) {
            var n = rows.addObject().put("id", id).put("name", id);
            n.putArray("aliases");
            n.putArray("parents");
        }
        ((com.fasterxml.jackson.databind.node.ArrayNode) rows.get(2).path("parents"))
                .add("rock")
                .add("jazz");
        var t = new GenreTaxonomy(d);
        assertEquals(Set.of("rock", "jazz"), t.nodes().get("fusion").parents());
        assertTrue(t.nodes().get("rock").hasChildren());
        assertFalse(t.nodes().containsKey("untrusted"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) rows.get(0).path("parents")).add("fusion");
        assertThrows(IllegalArgumentException.class, () -> new GenreTaxonomy(d));
    }

    @Test
    void consumesActualPythonOutputWhenSupplied() throws Exception {
        String path = System.getProperty("auralis.test.taxonomy");
        if (path == null) return;
        var document = JSON.readTree(Path.of(path).toFile());
        var taxonomy = new GenreTaxonomy(path);
        assertEquals(document.path("genres").size(), taxonomy.nodes().size());
        for (var row : document.path("genres")) {
            var node = taxonomy.nodes().get(row.path("id").asText());
            assertEquals(row.path("name").asText(), node.name());
            assertEquals(row.path("parents").size(), node.parents().size());
            for (var parent : row.path("parents"))
                assertTrue(node.parents().contains(parent.asText()));
        }
    }
}
