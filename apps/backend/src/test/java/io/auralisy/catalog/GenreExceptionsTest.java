package io.auralisy.catalog;

import static io.auralisy.catalog.application.CatalogJson.*;
import static org.junit.jupiter.api.Assertions.*;

import io.auralisy.catalog.api.*;
import io.auralisy.catalog.application.CandidateValidation;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class GenreExceptionsTest {
    static final String HASH = "sha256:" + "a".repeat(64);

    static Set<String> strings(com.fasterxml.jackson.databind.JsonNode array) {
        var set = new LinkedHashSet<String>();
        array.forEach(n -> set.add(n.asText()));
        return set;
    }

    @Test
    void actualCandidateValidationMatchesPythonSharedHierarchyCases() throws Exception {
        var fixture =
                JSON.readTree(
                        Path.of(System.getProperty("basedir"))
                                .resolveSibling(
                                        "intelligence/tests/fixtures/genre-exception-cases.json")
                                .toFile());
        var names = new LinkedHashMap<UUID, String>();
        var albums = new LinkedHashMap<UUID, CatalogQuery.Album>();
        int n = 0;
        for (var row : fixture.path("albums")) {
            UUID id = new UUID(0, ++n);
            names.put(id, row.path("name").asText());
            albums.put(
                    id,
                    new CatalogQuery.Album(
                            id,
                            "Album",
                            "Artist",
                            null,
                            null,
                            List.of(),
                            strings(row.path("genres")).stream()
                                    .map(g -> new CatalogQuery.Genre(g, g))
                                    .toList()));
        }
        var groups = new LinkedHashMap<String, Set<String>>();
        for (var genre : fixture.path("genres")) {
            var closure = new HashSet<>(Set.of(genre.asText()));
            boolean changed;
            do {
                changed = false;
                for (var edge : fixture.path("parents"))
                    if (closure.contains(edge.get(0).asText()))
                        changed |= closure.add(edge.get(1).asText());
            } while (changed);
            groups.put(genre.asText(), closure);
        }
        final int[] calls = {0};
        GenreQuery query =
                new GenreQuery() {
                    public List<Node> search(long d, String q, String p, int o, int l) {
                        throw new UnsupportedOperationException();
                    }

                    public Set<String> descendants(long d, Set<String> roots) {
                        throw new AssertionError("Use one batch closure lookup");
                    }

                    public Map<String, Node> nodes(long d, Set<String> roots) {
                        throw new UnsupportedOperationException();
                    }

                    public Map<String, Set<String>> descendantGroups(long d, Set<String> roots) {
                        calls[0]++;
                        return groups;
                    }
                };
        CatalogQuery catalog =
                (d, ids) -> {
                    var found = new LinkedHashMap<UUID, CatalogQuery.Album>();
                    for (UUID id : ids) if (albums.containsKey(id)) found.put(id, albums.get(id));
                    return found;
                };
        AudienceQuery audience =
                (d, b, ids) -> {
                    var found = new HashMap<UUID, Long>();
                    ids.forEach(id -> found.put(id, 50L));
                    return found;
                };
        var validator = new CandidateValidation(catalog, audience, query);
        var recall =
                JSON.createObjectNode()
                        .put("strategy_revision", "A-V2")
                        .put("data_snapshot_id", "train")
                        .put("window", "0")
                        .put("weights_digest", HASH);
        for (var test : fixture.path("cases")) {
            var context =
                    new CandidateValidation.Context(
                            1,
                            new AudienceQuery.Binding("full", "0", 100, HASH),
                            50,
                            Long.MAX_VALUE,
                            recall,
                            HASH,
                            HASH,
                            HASH,
                            "selection",
                            "policy",
                            Set.of(),
                            Set.of(),
                            strings(test.path("forbidden")),
                            strings(test.path("disliked")),
                            strings(test.path("positive")));
            var proposal =
                    JSON.createObjectNode()
                            .put("input_digest", HASH)
                            .put("effective_preference_digest", HASH)
                            .put("exclusion_digest", HASH)
                            .put("requested_count", albums.size())
                            .put("returned_count", albums.size())
                            .put("execution_status", "COMPLETE")
                            .put("raw_reason", "complete")
                            .put("shortfall", 0)
                            .put("truncated", false);
            proposal.set("recall_binding", recall);
            proposal.putArray("source_dependencies").add("audience:" + HASH);
            var rows = proposal.putArray("candidates");
            int rank = 0;
            for (UUID id : albums.keySet())
                rows.addObject().put("release_group_id", id.toString()).put("recall_rank", ++rank);
            proposal.put("proposal_digest", contentDigest(proposal, "proposal_digest"));
            var result = validator.validate("bundle", proposal, context);
            var actual = new ArrayList<String>();
            result.path("accepted")
                    .forEach(
                            c ->
                                    actual.add(
                                            names.get(
                                                    UUID.fromString(
                                                            c.path("release_group_id").asText()))));
            assertEquals(
                    new ArrayList<>(strings(test.path("accepted"))),
                    actual,
                    test.path("name").asText());
        }
        assertEquals(fixture.path("cases").size(), calls[0]);
    }
}
