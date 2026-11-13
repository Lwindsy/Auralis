package io.auralisy.discovery.adapters;

import static io.auralisy.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralisy.catalog.api.*;
import io.auralisy.catalog.application.CatalogDirectory;
import io.auralisy.discovery.api.*;
import io.auralisy.persistence.PersistenceRejected;
import io.auralisy.publication.api.PublicationAuthority;
import io.auralisy.session.api.SessionStore;
import java.util.*;

/** Fixed editorial order, verified catalogue identity and deterministic restrictions; no recall. */
public final class PreparedCuratedRecommendations implements CuratedRecommendations {
    private final CuratedContent content;
    private final String revision;
    private final CatalogDirectory directory;
    private final CatalogVersion versions;
    private final GenreQuery genres;
    private final SessionStore sessions;
    private final PublicationAuthority publication;

    public PreparedCuratedRecommendations(
            CuratedContent content,
            CatalogDirectory directory,
            CatalogVersion versions,
            GenreQuery genres,
            SessionStore sessions,
            PublicationAuthority publication) {
        CuratedContentFiles.validate(content);
        this.content = content;
        this.revision = "curated-v1:" + digest(JSON.valueToTree(content));
        this.directory = directory;
        this.versions = versions;
        this.genres = genres;
        this.sessions = sessions;
        this.publication = publication;
    }

    @Override
    public Batch candidates(SessionStore.Scope scope, String round, String text) {
        try {
            var pref = JSON.readTree(text);
            if (pref.path("has_positive_signal").asBoolean(true))
                throw new PersistenceRejected("CURATED_POSITIVE_INPUT");
            long version = versions.current();
            var excluded = new HashSet<>(sessions.heardAlbums(scope));
            for (var item : JSON.readTree(publication.exclusions(scope, round)))
                excluded.add(UUID.fromString(item.path("release_group_id").asText()));
            var forbidden = strings(pref.path("forbidden_tags"));
            var required = strings(pref.path("required_tags"));
            var disliked = strings(pref.path("disliked_tags"));
            var roots = new HashSet<>(forbidden);
            roots.addAll(required);
            roots.addAll(disliked);
            var groups = genres.descendantGroups(version, roots);
            var forbiddenAll = expand(forbidden, groups);
            var dislikedAll = expand(disliked, groups);
            var accepted = new ArrayList<Item>();
            var diagnostics = JSON.createArrayNode();
            var seen = new HashSet<UUID>();
            for (var album : content.albums()) {
                String reason = null;
                JsonNode identity = null;
                UUID id =
                        album.musicbrainz_release_group_id() == null
                                ? null
                                : UUID.fromString(album.musicbrainz_release_group_id());
                if (id == null) reason = "UNKNOWN_IDENTITY";
                else if (!album.review_status().equals("ready")) reason = "RESEARCH_INCOMPLETE";
                else {
                    try {
                        var resolved = directory.resolve(id).path("album");
                        id = UUID.fromString(resolved.path("mb_release_group_id").asText());
                        identity = resolved.path("identity");
                        if (!normalize(identity.path("title").asText())
                                        .equals(normalize(album.title()))
                                || !normalize(identity.path("artist_credit").asText())
                                        .equals(normalize(album.artist())))
                            reason = "IDENTITY_MISMATCH";
                    } catch (CatalogFailure e) {
                        if (e.status() != 404) throw e;
                        reason = "UNKNOWN_IDENTITY";
                    }
                    if (reason == null && excluded.contains(id)) reason = "EXCLUDED";
                    if (reason == null && !seen.add(id)) reason = "DUPLICATE_IDENTITY";
                    if (reason == null) {
                        var tags = new HashSet<String>();
                        identity.path("primary_genres")
                                .forEach(g -> tags.add(g.path("genre_id").asText()));
                        if (required.stream()
                                        .anyMatch(
                                                g ->
                                                        Collections.disjoint(
                                                                tags,
                                                                groups.getOrDefault(g, Set.of(g))))
                                || (!forbidden.isEmpty() && tags.isEmpty())
                                || !Collections.disjoint(tags, forbiddenAll)
                                || (!tags.isEmpty() && dislikedAll.containsAll(tags)))
                            reason = "HARD_CONSTRAINT";
                    }
                }
                diagnostics
                        .addObject()
                        .put("key", album.key())
                        .put("decision", reason == null ? "ACCEPTED" : reason);
                if (reason != null) continue;
                var shortReview =
                        JSON.createObjectNode()
                                .put("text", album.short_review().text())
                                .put("invocation_id", "curated-import:" + revision.substring(11))
                                .put("prompt_version", "curated-editorial-v1");
                shortReview.set("citations", citations(album.key(), album.short_review().refs()));
                var longReview =
                        JSON.createObjectNode()
                                .put("candidate_id", id.toString())
                                .put("status", "READY")
                                .put(
                                        "han_char_count",
                                        (int)
                                                album.long_review()
                                                        .text()
                                                        .codePoints()
                                                        .filter(
                                                                c ->
                                                                        Character.UnicodeScript.of(
                                                                                        c)
                                                                                == Character
                                                                                        .UnicodeScript
                                                                                        .HAN)
                                                        .count())
                                .put("prompt_version", "curated-editorial-v1")
                                .putNull("reason_code");
                longReview
                        .putObject("sections")
                        .put("production", album.long_review().text())
                        .put("instrumentation", "")
                        .put("vocals", "")
                        .put("mood", "");
                longReview.set("citations", citations(album.key(), album.long_review().refs()));
                longReview.putArray("extra_read_ids");
                longReview.putArray("missing_sections");
                longReview.putArray("invocation_ids");
                var provenance =
                        JSON.createObjectNode()
                                .put("origin", "OFFLINE_EDITORIAL")
                                .put("revision", revision)
                                .put("key", album.key());
                provenance.set("sources", JSON.valueToTree(album.sources()));
                accepted.add(
                        new Item(
                                id.toString(),
                                identity.toString(),
                                shortReview.toString(),
                                longReview.toString(),
                                provenance.toString()));
            }
            if (versions.current() != version)
                throw new PersistenceRejected("CATALOG_VERSION_CHANGED");
            return new Batch(revision, accepted, diagnostics.toString());
        } catch (java.io.IOException e) {
            throw new PersistenceRejected("INVALID_PAYLOAD");
        }
    }

    private static JsonNode citations(String key, List<String> refs) {
        var values = JSON.createArrayNode();
        refs.forEach(
                ref ->
                        values.addObject()
                                .put("marker", ref)
                                .put("read_id", "curated-source:" + key + ":" + ref));
        return values;
    }

    private static Set<String> strings(JsonNode values) {
        var result = new HashSet<String>();
        values.forEach(v -> result.add(v.asText()));
        return result;
    }

    private static Set<String> expand(Set<String> roots, Map<String, Set<String>> groups) {
        var result = new HashSet<String>();
        roots.forEach(root -> result.addAll(groups.getOrDefault(root, Set.of(root))));
        return result;
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(
                        value.replace("☮", "o"), java.text.Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
