package io.auralisy.preference.application;

import static io.auralisy.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralisy.catalog.api.*;
import io.auralisy.persistence.*;
import io.auralisy.preference.api.*;
import io.auralisy.session.api.SessionStore;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Atomic session guard, deterministic merge, immutable snapshot and idempotent reference receipt.
 */
@Service
public final class StoredPreferenceAuthority implements PreferenceAuthority {
    private final SessionStore sessions;
    private final PreferenceStore store;
    private final CatalogQuery catalog;
    private final GenreQuery genres;
    private final PreferenceMerge merge;
    private final ShortTransactions tx;

    public StoredPreferenceAuthority(
            SessionStore sessions,
            PreferenceStore store,
            CatalogQuery catalog,
            GenreQuery genres,
            PreferenceMerge merge,
            ShortTransactions tx) {
        this.sessions = sessions;
        this.store = store;
        this.catalog = catalog;
        this.genres = genres;
        this.merge = merge;
        this.tx = tx;
    }

    @Override
    public String resolve(Context context, String proposalJson) {
        if (context.commandId() == null
                || context.commandId().isBlank()
                || context.commandId().length() > 160
                || context.catalogDataset() < 1
                || context.referenceRevision() == null
                || context.referenceRevision().isBlank()
                || context.parserRevisions().isEmpty())
            throw new PreferenceRejected("PREFERENCE_BINDING_MISMATCH");
        JsonNode proposal = parse(proposalJson);
        merge.require("PreferenceResolutionProposal", proposal);
        if (!context.referenceRevision().equals(proposal.path("reference_revision").asText())
                || !context.parserRevisions().contains(proposal.path("parser_revision").asText()))
            throw new PreferenceRejected("PREFERENCE_BINDING_MISMATCH");
        return tx.write(
                () -> {
                    sessions.lockActive(context.scope());
                    var session =
                            sessions.current(context.scope().subjectId())
                                    .orElseThrow(
                                            () ->
                                                    new PersistenceRejected(
                                                            "STALE_SESSION_OR_BARRIER"));
                    if (!session.scope().equals(context.scope()))
                        throw new PersistenceRejected("STALE_SESSION_OR_BARRIER");
                    JsonNode raw = parse(session.rawInput());
                    merge.require("RawInput", raw);
                    if (!contentDigest(raw, "input_digest")
                                    .equals(raw.path("input_digest").asText())
                            || !raw.path("input_digest").equals(proposal.path("input_digest"))
                            || !contentDigest(proposal, "proposal_digest")
                                    .equals(proposal.path("proposal_digest").asText()))
                        throw new PreferenceRejected("PREFERENCE_BINDING_MISMATCH");
                    var binding =
                            JSON.createObjectNode()
                                    .put("session_id", context.scope().sessionId())
                                    .put("revision", context.scope().revision())
                                    .put("catalog_dataset", context.catalogDataset())
                                    .put("reference_revision", context.referenceRevision());
                    binding.set("proposal", proposal);
                    binding.put(
                            "reference_material_digest",
                            digest(JSON.valueToTree(context.genreNames())));
                    binding.put(
                            "external_identity_digest",
                            digest(JSON.valueToTree(context.externalSeeds())));
                    genres.nodes(context.catalogDataset(), Set.of());
                    String commandDigest = digest(binding);
                    var receipt =
                            sessions.command(
                                    context.scope(),
                                    "preference-resolution",
                                    context.commandId(),
                                    commandDigest);
                    if (receipt.isPresent()) {
                        String resolution = parse(receipt.get()).path("resolution_id").asText();
                        return store.find(context.scope(), resolution)
                                .orElseThrow(
                                        () ->
                                                new PreferenceRejected(
                                                        "PREFERENCE_SNAPSHOT_MISSING"));
                    }
                    var descriptions = verifyCatalog(context, raw, proposal);
                    var result =
                            merge.merge(
                                    raw,
                                    proposal,
                                    context.referenceRevision(),
                                    context.genreNames(),
                                    descriptions);
                    String resolution = result.path("resolution_id").asText();
                    store.save(
                            context.scope(),
                            resolution,
                            result.path("input_digest").asText(),
                            context.referenceRevision(),
                            proposal.path("parser_revision").asText(),
                            result.path("effective_preference_digest").asText(),
                            result.toString());
                    // No personal soft preferences in subject-scoped command receipts. Session
                    // deletion
                    // removes the only snapshot body; a receipt is never sufficient to recreate it.
                    String reference =
                            JSON.createObjectNode()
                                    .put("session_id", context.scope().sessionId())
                                    .put("resolution_id", resolution)
                                    .toString();
                    sessions.saveCommand(
                            context.scope(),
                            "preference-resolution",
                            context.commandId(),
                            commandDigest,
                            reference,
                            Instant.now());
                    return result.toString();
                });
    }

    private Map<String, String> verifyCatalog(Context context, JsonNode raw, JsonNode proposal) {
        var ids = new LinkedHashSet<String>();
        raw.path("liked_genres").forEach(r -> ids.add(r.path("genre_id").asText()));
        raw.path("disliked_genre_ids").forEach(r -> ids.add(r.asText()));
        for (String key : List.of("preferences", "constraints"))
            proposal.path(key).forEach(r -> ids.add(r.path("id").asText()));
        var albumIds = new ArrayList<UUID>();
        var seen = new HashSet<String>();
        for (var row : raw.path("liked_albums")) {
            String id = identifier(row);
            if (!seen.add(id)) throw new PreferenceRejected("DUPLICATE_ID");
            try {
                var uuid = UUID.fromString(id);
                if (!uuid.toString().equals(id)) throw new IllegalArgumentException();
                albumIds.add(uuid);
            } catch (IllegalArgumentException e) {
                throw new PreferenceRejected("UNKNOWN_IDENTITY");
            }
            if (row.path("release_group_id").isNull())
                row.path("external").path("mapped_genre_ids").forEach(g -> ids.add(g.asText()));
        }
        var nodes = genres.nodes(context.catalogDataset(), ids);
        if (!nodes.keySet().containsAll(ids) || !context.genreNames().keySet().containsAll(ids))
            throw new PreferenceRejected("INPUT_DESCRIPTION_UNAVAILABLE");
        var albums = catalog.albums(context.catalogDataset(), albumIds);
        var descriptions = new LinkedHashMap<String, String>();
        for (var row : raw.path("liked_albums")) {
            String id = identifier(row);
            var album = albums.get(UUID.fromString(id));
            if (!row.path("release_group_id").isNull()) {
                if (album == null) throw new PreferenceRejected("UNKNOWN_IDENTITY");
                if (!album.id().toString().equals(id))
                    throw new PreferenceRejected("STALE_INPUT_IDENTITY");
                descriptions.put(id, description(album.artistCredit(), album.title()));
            } else {
                var verified = context.externalSeeds().get(id);
                if (album != null) throw new PreferenceRejected("STALE_INPUT_IDENTITY");
                if (verified == null
                        || !verified.referenceRevision().equals(context.referenceRevision())
                        || !JSON.valueToTree(verified.mappedGenres())
                                .equals(row.path("external").path("mapped_genre_ids")))
                    throw new PreferenceRejected("INPUT_DESCRIPTION_UNAVAILABLE");
                descriptions.put(id, description(verified.artistCredit(), verified.title()));
            }
        }
        return descriptions;
    }

    private static String identifier(JsonNode row) {
        return row.path("release_group_id").isNull()
                ? row.path("external").path("mb_release_group_id").asText()
                : row.path("release_group_id").asText();
    }

    private static String description(String artist, String title) {
        if (artist == null || artist.isBlank() || title == null || title.isBlank())
            throw new PreferenceRejected("INPUT_DESCRIPTION_UNAVAILABLE");
        return artist + " / " + title;
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new PreferenceRejected("INVALID_PAYLOAD");
        }
    }
}
