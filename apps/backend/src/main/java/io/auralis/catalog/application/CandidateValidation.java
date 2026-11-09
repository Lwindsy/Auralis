package io.auralis.catalog.application;

import static io.auralis.catalog.application.CatalogJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.catalog.api.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Pure admission use case. Execution ownership, receipts and HTTP assembly belong to BE-010/003.
 */
@Service
public final class CandidateValidation {
    private final CatalogQuery catalog;
    private final AudienceQuery audience;
    private final GenreQuery genreQuery;

    public CandidateValidation(
            CatalogQuery catalog, AudienceQuery audience, GenreQuery genreQuery) {
        this.catalog = catalog;
        this.audience = audience;
        this.genreQuery = genreQuery;
    }

    /** Values must come from the coordinator's authorized execution, not the posted proposal. */
    public record Context(
            long catalogDataset,
            AudienceQuery.Binding audienceBinding,
            int audienceMinimum,
            JsonNode recallBinding,
            String inputDigest,
            String preferenceDigest,
            String exclusionDigest,
            String selectionRevision,
            String policyRevision,
            Set<UUID> excluded,
            Set<String> requiredGenres,
            Set<String> forbiddenGenres,
            Set<String> dislikedGenres,
            Set<String> explicitPositiveGenres) {
        public Context {
            recallBinding = recallBinding.deepCopy();
            excluded = Set.copyOf(excluded);
            requiredGenres = Set.copyOf(requiredGenres);
            forbiddenGenres = Set.copyOf(forbiddenGenres);
            dislikedGenres = Set.copyOf(dislikedGenres);
            explicitPositiveGenres = Set.copyOf(explicitPositiveGenres);
        }

        @Override
        public JsonNode recallBinding() {
            return recallBinding.deepCopy();
        }
    }

    public ObjectNode validate(String bundleId, JsonNode proposal, Context context) {
        require("RecallProposal", proposal);
        require("RecallBinding", context.recallBinding());
        if (!contentDigest(proposal, "proposal_digest")
                        .equals(proposal.path("proposal_digest").asText())
                || !proposal.path("recall_binding").equals(context.recallBinding())
                || !proposal.path("input_digest").asText().equals(context.inputDigest())
                || !proposal.path("effective_preference_digest")
                        .asText()
                        .equals(context.preferenceDigest())
                || !proposal.path("exclusion_digest").asText().equals(context.exclusionDigest()))
            throw new IllegalArgumentException("BINDING_MISMATCH");
        var rows = proposal.path("candidates");
        if (proposal.path("returned_count").asInt() != rows.size()
                || proposal.path("shortfall").asInt()
                        != proposal.path("requested_count").asInt() - rows.size())
            throw new IllegalArgumentException("COUNT_MISMATCH");
        boolean bound = false;
        for (var dependency : proposal.path("source_dependencies"))
            if (dependency
                    .asText()
                    .equals("audience:" + context.audienceBinding().snapshotDigest())) bound = true;
        if (!bound) throw new IllegalArgumentException("AUDIENCE_BINDING_MISMATCH");
        var ids = new ArrayList<UUID>();
        var seenIds = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String id = row.path("release_group_id").asText();
            if (!seenIds.add(id) || row.path("recall_rank").asInt() != i + 1)
                throw new IllegalArgumentException("RANK_OR_ID_MISMATCH");
            try {
                ids.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                /* Unknown identity is an explicit rejection. */
            }
        }
        var identities = catalog.albums(context.catalogDataset(), ids);
        var excludedMap =
                catalog.albums(context.catalogDataset(), new ArrayList<>(context.excluded()));
        var excluded = new HashSet<>(context.excluded());
        excludedMap.values().forEach(a -> excluded.add(a.id()));
        var canonical =
                identities.values().stream().map(CatalogQuery.Album::id).distinct().toList();
        var counts =
                audience.audience(context.catalogDataset(), context.audienceBinding(), canonical);
        var roots = new HashSet<>(context.forbiddenGenres());
        roots.addAll(context.dislikedGenres());
        roots.addAll(context.requiredGenres());
        roots.addAll(context.explicitPositiveGenres());
        var groups = genreQuery.descendantGroups(context.catalogDataset(), roots);
        var forbidden = new HashSet<String>();
        context.forbiddenGenres().forEach(id -> forbidden.addAll(groups.get(id)));
        var disliked =
                GenreRestrictions.disliked(
                        context.dislikedGenres(), context.explicitPositiveGenres(), groups);
        var required = context.requiredGenres().stream().map(groups::get).toList();
        var accepted = JSON.createArrayNode();
        var rejected = JSON.createArrayNode();
        var seen = new HashSet<UUID>();
        for (var row : rows) {
            String original = row.path("release_group_id").asText();
            CatalogQuery.Album album = null;
            try {
                album = identities.get(UUID.fromString(original));
            } catch (IllegalArgumentException ignored) {
            }
            String reason = null;
            if (album == null || album.artistCredit() == null || album.artistCredit().isBlank())
                reason = "UNKNOWN_IDENTITY";
            else if (excluded.contains(album.id())) reason = "EXCLUDED";
            else if (!seen.add(album.id())) reason = "DUPLICATE_IDENTITY";
            else {
                var genres =
                        new HashSet<>(
                                album.genres().stream().map(CatalogQuery.Genre::genreId).toList());
                if (required.stream().anyMatch(group -> Collections.disjoint(genres, group))
                        || (!forbidden.isEmpty() && genres.isEmpty())
                        || !Collections.disjoint(genres, forbidden)
                        || (!genres.isEmpty() && disliked.containsAll(genres)))
                    reason = "HARD_CONSTRAINT";
                else if (!counts.containsKey(album.id())) reason = "AUDIENCE_UNKNOWN";
                else if (counts.get(album.id()) < context.audienceMinimum())
                    reason = "AUDIENCE_BELOW_MINIMUM";
            }
            if (reason != null) {
                rejected.addObject().put("release_group_id", original).put("reason", reason);
                continue;
            }
            var out =
                    accepted.addObject()
                            .put("release_group_id", album.id().toString())
                            .put("recall_rank", row.path("recall_rank").asInt());
            if (!original.equals(album.id().toString()))
                out.put("proposed_release_group_id", original);
            var identity =
                    out.putObject("identity")
                            .put("title", album.title())
                            .put("artist_credit", album.artistCredit());
            if (album.year() == null) identity.putNull("first_release_year");
            else identity.put("first_release_year", album.year());
            if (album.primaryType() == null) identity.putNull("release_type");
            else identity.put("release_type", album.primaryType());
            var genres = identity.putArray("primary_genres");
            for (var g : album.genres())
                genres.addObject().put("genre_id", g.genreId()).put("name", g.name());
            out.putArray("known_review_refs");
            var b = context.audienceBinding();
            out.putObject("audience")
                    .put("source", "LISTENBRAINZ_SAMPLE")
                    .put("dataset_id", b.dataset())
                    .put("window", b.window())
                    .put("sample_listeners", b.sampleListeners())
                    .put("listeners", counts.get(album.id()));
        }
        String hash = digest(accepted);
        var result =
                JSON.createObjectNode().put("candidate_bundle_id", bundleId).put("digest", hash);
        result.set("accepted", accepted);
        result.set("rejected", rejected);
        result.putObject("counts")
                .put("proposed", rows.size())
                .put("accepted", accepted.size())
                .put("rejected", rejected.size());
        result.putObject("binding")
                .put("candidate_bundle_digest", hash)
                .put("effective_preference_digest", context.preferenceDigest())
                .put("exclusion_digest", context.exclusionDigest())
                .put("selection_config_revision", context.selectionRevision())
                .put("agent_policy_revision", context.policyRevision())
                .set("recall_binding", context.recallBinding());
        require("CandidateBundle", result);
        return result;
    }
}
