package io.auralis.catalog.application;

import static io.auralis.catalog.application.CatalogJson.JSON;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.catalog.api.*;
import io.auralis.contracts.MessageDefinitions;
import java.util.*;

/**
 * Maps verified public metadata to the existing product contract. No personal writes or network
 * transaction.
 */
public final class CatalogDirectory {
    private final CatalogSearchPort remote;
    private final CatalogQuery catalog;
    private final GenreQuery genres;
    private final CatalogVersion versions;
    private final MessageDefinitions definitions;

    public CatalogDirectory(
            CatalogSearchPort remote,
            CatalogQuery catalog,
            GenreQuery genres,
            CatalogVersion versions,
            MessageDefinitions definitions) {
        this.remote = remote;
        this.catalog = catalog;
        this.genres = genres;
        this.versions = versions;
        this.definitions = definitions;
    }

    public JsonNode search(String query, int offset, int limit) {
        page(query, offset, limit, true);
        long version = versions.current();
        var found = remote.search(query, offset, limit);
        var local =
                catalog.albums(
                        version, found.albums().stream().map(CatalogSearchPort.Album::id).toList());
        var result = JSON.createObjectNode();
        var items = result.putArray("items");
        var seen = new HashSet<UUID>();
        var genreIds = new HashSet<String>();
        for (var candidate : found.albums())
            if (!local.containsKey(candidate.id()))
                for (var g : candidate.genres()) genreIds.add(g.genreId());
        var admitted = genres.nodes(version, genreIds);
        for (var album : found.albums()) {
            var core = local.get(album.id());
            UUID canonical = core == null ? album.id() : core.id();
            if (seen.add(canonical)) items.add(album(album, core, admitted));
        }
        result.put("offset", offset).put("limit", limit);
        if (found.nextOffset() == null) result.putNull("next_offset");
        else result.put("next_offset", found.nextOffset());
        sameVersion(version);
        return checked("AlbumSearchResult", result);
    }

    public JsonNode resolve(UUID id) {
        long version = versions.current();
        // Selection always uses a lookup, never trusts an earlier search row or browser labels.
        var external = remote.resolve(id);
        var core = catalog.albums(version, List.of(external.id())).get(external.id());
        var genreIds = new HashSet<String>();
        for (var g : external.genres()) genreIds.add(g.genreId());
        var value = album(external, core, genres.nodes(version, genreIds));
        var input = JSON.createObjectNode().put("strength", "normal").put("focused", false);
        if (core != null) input.put("release_group_id", core.id().toString()).putNull("external");
        else {
            input.putNull("release_group_id");
            var mapped =
                    input.putObject("external")
                            .put("mb_release_group_id", external.id().toString())
                            .putArray("mapped_genre_ids");
            for (var g : value.path("identity").path("primary_genres"))
                mapped.add(g.path("genre_id").asText());
        }
        var result =
                JSON.createObjectNode()
                        .put(
                                "catalog_revision",
                                "catalog:" + version + "/search:" + remote.revision());
        result.set("album", value);
        result.set("input", input);
        sameVersion(version);
        return checked("ResolvedAlbum", result);
    }

    public JsonNode genres(String query, String parent, int offset, int limit) {
        page(query, offset, limit, false);
        if (parent != null && (parent.isBlank() || parent.codePointCount(0, parent.length()) > 160))
            throw invalid();
        long version = versions.current();
        var nodes = genres.search(version, query, parent, offset, limit);
        boolean more =
                offset + limit <= 10000
                        && !genres.search(version, query, parent, offset + limit, 1).isEmpty();
        var result =
                JSON.createObjectNode()
                        .put("reference_revision", "catalog:" + version)
                        .put("offset", offset)
                        .put("limit", limit);
        var items = result.putArray("items");
        for (var n : nodes) {
            var item =
                    items.addObject()
                            .put("genre_id", n.id())
                            .put("name", n.name())
                            .put("has_children", n.hasChildren());
            var parents = item.putArray("parent_ids");
            n.parents().stream().sorted().forEach(parents::add);
        }
        if (more) result.put("next_offset", offset + limit);
        else result.putNull("next_offset");
        sameVersion(version);
        return checked("GenreSearchResult", result);
    }

    private ObjectNode album(
            CatalogSearchPort.Album remote,
            CatalogQuery.Album core,
            Map<String, GenreQuery.Node> admitted) {
        var result =
                JSON.createObjectNode()
                        .put(
                                "mb_release_group_id",
                                (core == null ? remote.id() : core.id()).toString())
                        .put("in_core_catalog", core != null);
        var identity = result.putObject("identity");
        identity.put("title", core == null ? remote.title() : core.title());
        identity.put("artist_credit", core == null ? remote.artist() : core.artistCredit());
        Integer year = core == null ? remote.year() : core.year();
        if (year == null) identity.putNull("first_release_year");
        else identity.put("first_release_year", year);
        String type = core == null ? remote.type() : core.primaryType();
        if (type == null) identity.putNull("release_type");
        else identity.put("release_type", type);
        var mapped = identity.putArray("primary_genres");
        if (core != null)
            core.genres()
                    .forEach(
                            g ->
                                    mapped.addObject()
                                            .put("genre_id", g.genreId())
                                            .put("name", g.name()));
        else {
            var ids = new HashSet<String>();
            for (var g : remote.genres()) ids.add(g.genreId());
            // Only identical, admitted genre IDs cross the boundary; no fuzzy label inference.
            admitted.values().stream()
                    .filter(g -> ids.contains(g.id()))
                    .sorted(Comparator.comparing(GenreQuery.Node::id))
                    .forEach(g -> mapped.addObject().put("genre_id", g.id()).put("name", g.name()));
        }
        return result;
    }

    private void sameVersion(long version) {
        if (versions.current() != version) throw new CatalogFailure(503, "CATALOG_VERSION_CHANGED");
    }

    private JsonNode checked(String name, JsonNode value) {
        if (!definitions.validDefinition(name, value))
            throw new CatalogFailure(503, "CATALOG_INVALID_RESPONSE");
        return value;
    }

    private static CatalogFailure invalid() {
        return new CatalogFailure(400, "INVALID_PAYLOAD");
    }

    private static void page(String query, int offset, int limit, boolean required) {
        if (offset < 0
                || offset > 10000
                || limit < 1
                || limit > 50
                || (required && query == null)
                || (query != null
                        && (query.isBlank() || query.codePointCount(0, query.length()) > 200)))
            throw invalid();
    }
}
