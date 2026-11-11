package io.auralis.discovery.application;

import static io.auralis.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.budget.api.BudgetAuthority;
import io.auralis.catalog.api.*;
import io.auralis.catalog.application.CandidateValidation;
import io.auralis.catalog.application.CatalogDirectory;
import io.auralis.contracts.MessageDefinitions;
import io.auralis.discovery.api.*;
import io.auralis.persistence.*;
import io.auralis.preference.api.*;
import io.auralis.publication.api.PublicationAuthority;
import io.auralis.session.api.*;
import io.auralis.session.api.SessionStore.Scope;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/** Browser and internal use-case composition; every state write stays with its owning port. */
@Service
public class Recommendations {
    private final SubjectSession subjects;
    private final SessionStore sessions;
    private final DiscoveryStore discovery;
    private final DispatchQueue queue;
    private final DispatchQueue.Limits limits;
    private final PublicationAuthority publication;
    private final PreferenceAuthority preferences;
    private final PreferenceStore preferenceStore;
    private final CatalogVersion versions;
    private final CatalogQuery catalog;
    private final GenreQuery genres;
    private final AudienceQuery audience;
    private final CatalogDirectory directory;
    private final CandidateValidation candidates;
    private final RecommendationPolicy policy;
    private final CuratedRecommendations curated;
    private final MessageDefinitions schema;
    private final ShortTransactions tx;
    private final ExecutionReadAccess access;
    private final Clock clock;

    public Recommendations(
            SubjectSession subjects,
            SessionStore sessions,
            DiscoveryStore discovery,
            DispatchQueue queue,
            DispatchQueue.Limits limits,
            PublicationAuthority publication,
            PreferenceAuthority preferences,
            PreferenceStore preferenceStore,
            CatalogVersion versions,
            CatalogQuery catalog,
            GenreQuery genres,
            AudienceQuery audience,
            CatalogDirectory directory,
            CandidateValidation candidates,
            RecommendationPolicy policy,
            CuratedRecommendations curated,
            MessageDefinitions schema,
            ShortTransactions tx,
            ExecutionReadAccess access,
            Clock clock) {
        this.subjects = subjects;
        this.sessions = sessions;
        this.discovery = discovery;
        this.queue = queue;
        this.limits = limits;
        this.publication = publication;
        this.preferences = preferences;
        this.preferenceStore = preferenceStore;
        this.versions = versions;
        this.catalog = catalog;
        this.genres = genres;
        this.audience = audience;
        this.directory = directory;
        this.candidates = candidates;
        this.policy = policy;
        this.curated = curated;
        this.schema = schema;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    private static void fail(String code) {
        throw new PersistenceRejected(code);
    }

    private JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new PersistenceRejected("INVALID_PAYLOAD");
        }
    }

    private JsonNode parse(String text, String type) {
        var n = json(text);
        if (!schema.validDefinition(type, n)) fail("INVALID_PAYLOAD");
        return n;
    }

    private <T> T convert(JsonNode n, Class<T> type) {
        try {
            return JSON.treeToValue(n, type);
        } catch (Exception e) {
            throw new PersistenceRejected("RECOMMENDATION_UNAVAILABLE");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Scope owned(String token, String session) {
        var identity =
                subjects.authenticate(token).orElseThrow(() -> new PersistenceRejected("EXPIRED"));
        var current =
                sessions.current(identity.subjectId())
                        .orElseThrow(() -> new PersistenceRejected("RESOURCE_UNAVAILABLE"));
        if (session != null && !session.equals(current.scope().sessionId()))
            fail("RESOURCE_UNAVAILABLE");
        if (current.scope().subjectEpoch() != identity.epoch()) fail("RESOURCE_UNAVAILABLE");
        sessions.lockActive(current.scope());
        return current.scope();
    }

    private record Input(ObjectNode raw, ObjectNode external) {}

    private Input resolve(JsonNode input, long dataset, JsonNode settings) {
        var raw = (ObjectNode) input.deepCopy();
        var liked = raw.putArray("liked_albums");
        var external = JSON.createObjectNode();
        var seen = new HashSet<UUID>();
        var genreIds = new HashSet<String>();
        input.path("liked_genres")
                .forEach(
                        n -> {
                            if (!genreIds.add(n.path("genre_id").asText())) fail("DUPLICATE_INPUT");
                        });
        input.path("disliked_genre_ids").forEach(n -> genreIds.add(n.asText()));
        if (!genres.nodes(dataset, genreIds).keySet().containsAll(genreIds)) fail("UNKNOWN_GENRE");
        for (var item : input.path("liked_albums")) {
            UUID id;
            try {
                id = UUID.fromString(item.path("mb_release_group_id").asText());
            } catch (Exception e) {
                throw new PersistenceRejected("UNKNOWN_IDENTITY");
            }
            var album = catalog.albums(dataset, List.of(id)).get(id);
            var next =
                    liked.addObject()
                            .put("strength", item.path("strength").asText())
                            .put("focused", item.path("focused").asBoolean());
            if (album != null) {
                id = album.id();
                next.put("release_group_id", id.toString()).putNull("external");
            } else {
                var resolved = directory.resolve(id);
                var canonical =
                        UUID.fromString(
                                resolved.path("album").path("mb_release_group_id").asText());
                id = canonical;
                next.set("release_group_id", resolved.path("input").path("release_group_id"));
                next.set("external", resolved.path("input").path("external"));
                if (!next.path("external").isNull()) {
                    var source =
                            external.putObject(id.toString())
                                    .put(
                                            "title",
                                            resolved.path("album")
                                                    .path("identity")
                                                    .path("title")
                                                    .asText())
                                    .put(
                                            "artistCredit",
                                            resolved.path("album")
                                                    .path("identity")
                                                    .path("artist_credit")
                                                    .asText())
                                    .put(
                                            "referenceRevision",
                                            settings.path("reference_revision").asText());
                    source.set("mappedGenres", next.path("external").path("mapped_genre_ids"));
                }
            }
            if (!seen.add(id)) fail("DUPLICATE_INPUT");
        }
        raw.put("input_digest", contentDigest(raw, "input_digest"));
        parse(raw.toString(), "RawInput");
        return new Input(raw, external);
    }

    private ObjectNode configuration(
            JsonNode settings, long dataset, JsonNode external, Instant accepted) {
        var c =
                JSON.createObjectNode()
                        .put("catalog_dataset", dataset)
                        .put("accepted_at", accepted.toString())
                        .putNull("preference_resolution")
                        .put("curated_pending", false);
        c.set("settings", settings);
        c.set("external_seeds", external);
        return c;
    }

    private CuratedRecommendations.Batch curatedCandidates(
            Scope scope, String round, String preferences, JsonNode config) {
        var batch = curated.candidates(scope, round, preferences);
        var raw = json(sessions.current(scope.subjectId()).orElseThrow().rawInput());
        if (!raw.has("audience_min_listeners")) return batch;
        var agentPolicy = config.path("settings").path("agent_policy");
        int minimum = agentPolicy.path("audience_min_listeners").asInt();
        long maximum = agentPolicy.path("audience_max_listeners").asLong(Long.MAX_VALUE);
        var ids = batch.items().stream().map(item -> UUID.fromString(item.id())).toList();
        var counts =
                audience.audience(config.path("catalog_dataset").asLong(), population(config), ids);
        var accepted = new ArrayList<CuratedRecommendations.Item>();
        var diagnostics =
                (com.fasterxml.jackson.databind.node.ArrayNode)
                        json(batch.diagnostics()).deepCopy();
        for (var item : batch.items()) {
            var count = counts.get(UUID.fromString(item.id()));
            if (count != null && count >= minimum && count <= maximum) accepted.add(item);
            else
                diagnostics
                        .addObject()
                        .put("release_group_id", item.id())
                        .put(
                                "decision",
                                count == null
                                        ? "AUDIENCE_UNKNOWN"
                                        : count < minimum
                                                ? "AUDIENCE_BELOW_MINIMUM"
                                                : "AUDIENCE_ABOVE_MAXIMUM");
        }
        return new CuratedRecommendations.Batch(batch.revision(), accepted, diagnostics.toString());
    }

    private AudienceQuery.Binding population(JsonNode config) {
        return convert(
                config.path("settings").path("audience_binding"), AudienceQuery.Binding.class);
    }

    private PreferenceAuthority.Context preferenceContext(
            Scope scope, String command, JsonNode config) {
        var names = new HashMap<String, String>();
        config.path("settings")
                .path("reference_genres")
                .fields()
                .forEachRemaining(e -> names.put(e.getKey(), e.getValue().asText()));
        var parsers = new HashSet<String>();
        config.path("settings").path("parser_revisions").forEach(n -> parsers.add(n.asText()));
        var external = new HashMap<String, PreferenceAuthority.ExternalSeed>();
        config.path("external_seeds")
                .fields()
                .forEachRemaining(
                        e ->
                                external.put(
                                        e.getKey(),
                                        convert(
                                                e.getValue(),
                                                PreferenceAuthority.ExternalSeed.class)));
        return new PreferenceAuthority.Context(
                scope,
                command,
                config.path("catalog_dataset").asLong(),
                config.path("settings").path("reference_revision").asText(),
                parsers,
                names,
                external);
    }

    private JsonNode effective(Scope scope, JsonNode config) {
        String resolution = config.path("preference_resolution").asText();
        if (resolution.isBlank() || config.path("preference_resolution").isNull())
            return JSON.nullNode();
        return json(
                preferenceStore
                        .find(scope, resolution)
                        .orElseThrow(() -> new PersistenceRejected("PREFERENCE_UNAVAILABLE")));
    }

    private JsonNode exclusions(Scope scope, String currentRound) {
        var ids = new TreeMap<String, String>();
        var raw = json(sessions.current(scope.subjectId()).orElseThrow().rawInput());
        for (var seed : raw.path("liked_albums"))
            if (seed.path("release_group_id").isTextual())
                ids.put(seed.path("release_group_id").asText(), "SEED");
        sessions.heardAlbums(scope).forEach(id -> ids.putIfAbsent(id.toString(), "HEARD"));
        for (var row : json(publication.exclusions(scope, currentRound)))
            ids.putIfAbsent(row.path("release_group_id").asText(), row.path("reason").asText());
        var out = JSON.createArrayNode();
        ids.forEach(
                (id, reason) -> out.addObject().put("release_group_id", id).put("reason", reason));
        return out;
    }

    private Map<String, BudgetAuthority.Price> prices(JsonNode config) {
        var map = new HashMap<String, BudgetAuthority.Price>();
        config.path("settings")
                .path("prices")
                .fields()
                .forEachRemaining(
                        e ->
                                map.put(
                                        e.getKey(),
                                        convert(e.getValue(), BudgetAuthority.Price.class)));
        return map;
    }

    private String scopeId(String kind, String id) {
        return digest(JSON.getNodeFactory().textNode(kind + ":" + id));
    }

    private ObjectNode plan(
            Scope scope,
            DiscoveryStore.Round round,
            JsonNode config,
            String operation,
            Set<String> targets,
            boolean continuing,
            Instant accepted) {
        var settings = config.path("settings");
        var out =
                JSON.createObjectNode()
                        .put("execution_id", "pending-execution")
                        .put("operation", "DISCOVERY_ROUND")
                        .put("start_mode", continuing ? "CONTINUE" : "FRESH")
                        .put("session_id", scope.sessionId())
                        .put("round_id", round.id())
                        .put("round_seq", round.sequence())
                        .put(
                                "parent_round_id",
                                config.path("parent_round").isTextual()
                                        ? config.path("parent_round").asText()
                                        : null)
                        .put("run_id", "pending-run")
                        .put("run_kind", operation.equals("REGENERATE") ? "RERUN" : operation)
                        .put("foreground", !operation.equals("REGENERATE"))
                        .put("erasure_handle", scopeId("subject", scope.subjectId()));
        out.putObject("boot")
                .put("coordinator_boot_id", "pending-coordinator")
                .put("python_boot_id", "pending-python");
        for (String key :
                List.of(
                        "selection_config",
                        "agent_policy",
                        "model_bindings",
                        "recall_binding",
                        "web_guards",
                        "capabilities")) out.set(key, settings.path(key));
        var b =
                out.putObject("budget")
                        .put("session_budget_scope_id", scopeId("session", scope.sessionId()))
                        .put("round_budget_scope_id", scopeId("round", round.id()));
        b.set("limits", settings.path("budget_limits"));
        b.putObject("remaining_at_issue")
                .put("session", 0)
                .put("round", 0)
                .put("FOREGROUND", 0)
                .put("BACKGROUND", 0);
        b.putObject("structure_repair").put("foreground_per_run", 1).put("background_per_round", 1);
        out.putObject("deadlines")
                .put(
                        "round_deadline_at",
                        accepted.plusSeconds(limits.executionSeconds()).toString())
                .put("idle_close_ms", settings.path("idle_close_ms").asInt());
        var preferences = effective(scope, config);
        var raw = json(sessions.current(scope.subjectId()).orElseThrow().rawInput());
        var input = out.putObject("input");
        input.set("accepted_preferences", preferences);
        input.set("raw", preferences.isNull() ? raw : JSON.nullNode());
        if ("en".equals(raw.path("language").asText())) input.put("language", "en");
        var excludes = exclusions(scope, round.id());
        input.set("exclusions", excludes);
        input.put("exclusion_digest", digest(excludes));
        if (continuing)
            out.set(
                    "continuation",
                    json(
                            publication.continuation(
                                    scope, round.id(), operation, targets, digest(excludes))));
        else out.putNull("continuation");
        out.put("payload_digest", contentDigest(out, "payload_digest"));
        if (!schema.validDefinition("RoundStart", out)) fail("INVALID_EXECUTION_CONFIGURATION");
        return out;
    }

    private DispatchQueue.Accepted enqueue(
            Scope scope,
            DiscoveryStore.Round round,
            ObjectNode config,
            String operation,
            String key,
            Set<String> targets,
            boolean continuing,
            Instant accepted) {
        // A new execution may use updated technical token limits for the same model and
        // tariff. Keep the round's business policy, monetary caps and historical executions.
        config = config.deepCopy();
        var configured = json(policy.snapshot());
        var settings = (ObjectNode) config.path("settings");
        settings.path("model_bindings")
                .fields()
                .forEachRemaining(
                        entry -> {
                            var prior = entry.getValue();
                            var current = configured.path("model_bindings").path(entry.getKey());
                            if (!current.isObject()) return;
                            var oldIdentity = (ObjectNode) prior.deepCopy();
                            var newIdentity = (ObjectNode) current.deepCopy();
                            oldIdentity.remove(List.of("max_input_tokens", "max_output_tokens"));
                            newIdentity.remove(List.of("max_input_tokens", "max_output_tokens"));
                            if (!oldIdentity.equals(newIdentity)) return;
                            String ref = prior.path("ref").asText();
                            var oldPrice =
                                    (ObjectNode) settings.path("prices").path(ref).deepCopy();
                            var newPrice =
                                    (ObjectNode) configured.path("prices").path(ref).deepCopy();
                            oldPrice.remove(List.of("maxInput", "maxOutput"));
                            newPrice.remove(List.of("maxInput", "maxOutput"));
                            if (oldPrice.equals(newPrice)) {
                                ((ObjectNode) settings.path("model_bindings"))
                                        .set(entry.getKey(), current.deepCopy());
                                ((ObjectNode) settings.path("prices"))
                                        .set(ref, configured.path("prices").path(ref).deepCopy());
                            }
                        });
        discovery.extendDeadline(
                scope, round.id(), accepted.plusSeconds(limits.executionSeconds()));
        var start = plan(scope, round, config, operation, targets, continuing, accepted);
        var mapping = new HashMap<String, String>();
        JsonNode bound = continuing ? start.path("continuation").path("candidate_bundle") : null;
        if (bound != null)
            bound.path("accepted")
                    .forEach(
                            n ->
                                    mapping.put(
                                            n.path("release_group_id").asText(),
                                            n.path("release_group_id").asText()));
        var result =
                queue.accept(
                        new DispatchQueue.Request(
                                scope,
                                round.id(),
                                operation.equals("REGENERATE") ? "RERUN" : operation,
                                key,
                                start.toString(),
                                prices(config),
                                config.path("catalog_dataset").asLong(),
                                mapping,
                                targets,
                                accepted),
                        limits);

        return result;
    }

    public String submit(String token, String csrf, String key, String body) {
        Instant accepted = now();
        var request = parse(body, "DiscoverySubmission");
        if (request.path("input").path("liked_genres").isEmpty()
                && request.path("input").path("disliked_genre_ids").isEmpty()
                && request.path("input").path("liked_albums").isEmpty()
                && request.path("input").path("request_text").asText().isBlank())
            fail("INPUT_REQUIRED");
        if (!subjects.validCsrf(token, csrf)) fail("FORBIDDEN");
        var settings = json(policy.snapshot());
        var requestedInput = request.path("input");
        if (requestedInput.has("audience_min_listeners")) {
            var agentPolicy = (ObjectNode) settings.path("agent_policy");
            agentPolicy.set(
                    "audience_min_listeners", requestedInput.path("audience_min_listeners"));
            if (requestedInput.has("audience_max_listeners")) {
                var maximum = requestedInput.path("audience_max_listeners");
                if (maximum.asInt() < requestedInput.path("audience_min_listeners").asInt())
                    fail("INVALID_PAYLOAD");
                agentPolicy.set("audience_max_listeners", maximum);
            } else {
                agentPolicy.remove("audience_max_listeners");
            }
            agentPolicy.put("digest", contentDigest(agentPolicy, "digest"));
        }
        long dataset = versions.current();
        var input = resolve(request.path("input"), dataset, settings);
        var reuse = reuse(token, key, request, input, settings, dataset);
        if (reuse.cached() != null) return reuse.cached();
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    if (versions.current() != dataset) fail("CATALOG_VERSION_CHANGED");
                    if (reuse.eligible()) {
                        var current = owned(token, request.path("expected_session_id").asText());
                        if (current.revision() != request.path("expected_revision").asLong())
                            fail("REVISION_CONFLICT");
                        subjects.renew(token, csrf, current);
                        var latest = sessions.refresh(current);
                        String result = state(token, latest.sessionId(), false);
                        sessions.saveCommand(
                                latest,
                                "submit-reuse",
                                key,
                                digest(request),
                                result,
                                clock.instant());
                        return result;
                    }
                    var replacement =
                            subjects.replace(
                                    token,
                                    csrf,
                                    key,
                                    request.path("expected_session_id").isNull()
                                            ? null
                                            : request.path("expected_session_id").asText(),
                                    request.path("expected_revision").asLong(),
                                    request.path("input").toString(),
                                    input.raw().toString());
                    if (replacement.replayed())
                        return state(token, replacement.scope().sessionId(), false);
                    var scope = replacement.scope();
                    String roundId = UUID.randomUUID().toString();
                    var config = configuration(settings, dataset, input.external(), accepted);
                    if (input.raw().path("request_text").asText().isBlank()) {
                        var proposal =
                                JSON.createObjectNode()
                                        .put(
                                                "input_digest",
                                                input.raw().path("input_digest").asText())
                                        .put(
                                                "reference_revision",
                                                settings.path("reference_revision").asText())
                                        .put(
                                                "parser_revision",
                                                settings.path("parser_revisions").get(0).asText());
                        for (String field :
                                List.of(
                                        "preferences",
                                        "constraints",
                                        "unresolved_constraints",
                                        "soft_preferences")) proposal.putArray(field);
                        proposal.put("proposal_digest", contentDigest(proposal, "proposal_digest"));
                        var snapshot =
                                json(
                                        preferences.resolve(
                                                preferenceContext(
                                                        scope, "structured:" + roundId, config),
                                                proposal.toString()));
                        config.put(
                                "preference_resolution", snapshot.path("resolution_id").asText());
                    }
                    discovery.createRound(
                            scope,
                            roundId,
                            1,
                            config.toString(),
                            settings.path("recall_binding").toString(),
                            accepted.plusSeconds(limits.executionSeconds()));
                    var round = discovery.roundInfo(scope, roundId);
                    var snapshot = effective(scope, config);
                    if (!snapshot.isNull() && !snapshot.path("has_positive_signal").asBoolean()) {
                        var fixed = curatedCandidates(scope, roundId, snapshot.toString(), config);
                        String run = scopeId("curated", key);
                        discovery.createIntent(
                                scope,
                                roundId,
                                run,
                                "INITIAL",
                                sessions.inputRevision(scope),
                                digest(request),
                                accepted,
                                accepted.plusSeconds(limits.queueSeconds()));
                        if (!discovery.transitionRun(scope, run, 0, "WAITING", "SUCCEEDED"))
                            fail("REVISION_CONFLICT");
                        config.put("curated_prepared", true)
                                .put("curated_revision", fixed.revision());
                        discovery.configureRound(scope, roundId, config.toString());
                        publication.publishCurated(
                                scope,
                                roundId,
                                run,
                                settings.path("selection_config")
                                        .path("recommendation_target_count")
                                        .asInt(),
                                fixed);
                        return state(token, scope.sessionId(), false);
                    }
                    audience.audience(
                            dataset,
                            convert(settings.path("audience_binding"), AudienceQuery.Binding.class),
                            List.of());
                    var queued =
                            enqueue(
                                    scope,
                                    round,
                                    config,
                                    "INITIAL",
                                    scopeId("submit", key),
                                    Set.of(),
                                    false,
                                    accepted);
                    publication.begin(
                            scope,
                            roundId,
                            queued.run(),
                            settings.path("selection_config")
                                    .path("recommendation_target_count")
                                    .asInt(),
                            "WAITING");
                    return state(token, scope.sessionId(), false);
                });
    }

    public String state(String token, String session, boolean restored) {
        return tx.write(
                () -> {
                    var recovery = (ObjectNode) json(subjects.restore(token, session));
                    if (recovery.path("session_id").isNull()) return recovery.toString();
                    var scope = owned(token, recovery.path("session_id").asText());
                    var current = discovery.latestRound(scope);
                    if (current.isEmpty()) return recovery.toString();
                    var value = (ObjectNode) json(publication.state(scope, current.get().id()));
                    value.set("editable_input", recovery.path("editable_input"));
                    value.set("expires_at", recovery.path("expires_at"));
                    value.put("restored", restored);
                    return value.toString();
                });
    }

    public String longReview(String token, String session, String candidate) {
        return tx.write(
                () -> {
                    var scope = owned(token, session);
                    return publication.longReview(
                            scope, publication.candidateRound(scope, candidate), candidate);
                });
    }

    private String supply(
            Scope scope, String roundId, String operation, String key, Set<String> targets) {
        if (json(discovery.roundInfo(scope, roundId).configuration())
                .path("curated_prepared")
                .asBoolean()) return null;
        if (operation.equals("SUPPLY")) {
            var merged = queue.mergeSupply(scope, roundId, key, targets, limits);
            if (merged.isPresent()) return merged.get().run();
        }
        var round = discovery.roundInfo(scope, roundId);
        var config = (ObjectNode) json(round.configuration());
        boolean next =
                operation.equals("SUPPLY")
                        && json(publication.state(scope, roundId))
                                .path("supply_state")
                                .asText()
                                .equals("R_EXHAUSTED");
        Instant accepted = now();
        if (next) {
            if (round.sequence() >= 5) return null;
            String id = UUID.randomUUID().toString();
            config.put("parent_round", round.id())
                    .put("accepted_at", accepted.toString())
                    .put("curated_pending", false);
            discovery.createRound(
                    scope,
                    id,
                    round.sequence() + 1,
                    config.toString(),
                    config.path("settings").path("recall_binding").toString(),
                    accepted.plusSeconds(limits.executionSeconds()));
            round = discovery.roundInfo(scope, id);
        }
        return enqueue(scope, round, config, operation, key, targets, !next, accepted).run();
    }

    public String action(
            String token,
            String csrf,
            String key,
            String session,
            String operation,
            String target,
            String body) {
        Instant actionAt = now();
        var input = parse(body, operation.equals("CARD") ? "CardAction" : "SessionAction");
        if (!subjects.validCsrf(token, csrf)) fail("FORBIDDEN");
        var intent =
                JSON.createObjectNode()
                        .put("operation", operation)
                        .put("target", target)
                        .set("body", input);
        String hash = digest(intent);
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    var current = owned(token, session);
                    var cached = sessions.command(current, "product:" + operation, key, hash);
                    if (cached.isPresent()) return cached.get();
                    if (current.revision() != input.path("expected_revision").asLong())
                        fail("REVISION_CONFLICT");
                    var round =
                            discovery
                                    .latestRound(current)
                                    .orElseThrow(
                                            () -> new PersistenceRejected("RESOURCE_UNAVAILABLE"));
                    boolean isLong = Set.of("OPEN", "REGENERATE").contains(operation);
                    if (operation.equals("CARD") || operation.equals("BATCH"))
                        publication.operate(
                                current,
                                round.id(),
                                scopeId("product", key),
                                operation.equals("BATCH") ? "BATCH" : input.path("action").asText(),
                                target,
                                (s, r, slots) ->
                                        supply(s, r, "SUPPLY", scopeId("supply", key), slots));
                    else if (operation.equals("UNDO"))
                        publication.undoLastCard(current, round.id());
                    else if (operation.equals("RESCREEN")) {
                        publication.discardUndo(current);
                        var before = json(publication.state(current, round.id()));
                        if (!before.path("analysis_mode").asText().equals("BASIC"))
                            fail("INVALID_OPERATION");
                        String run =
                                supply(
                                        current,
                                        round.id(),
                                        "RESCREEN",
                                        scopeId("rescreen", key),
                                        Set.of());
                        publication.waitFor(current, round.id(), run);
                    } else if (operation.equals("OPEN")) {
                        publication.longReview(
                                current, publication.candidateRound(current, target), target);
                        queue.prioritize(current, target, key);
                    } else if (operation.equals("REGENERATE")
                            && json(round.configuration()).path("curated_prepared").asBoolean())
                        publication.longReview(
                                current, publication.candidateRound(current, target), target);
                    else if (operation.equals("REGENERATE"))
                        publication.regenerate(
                                current,
                                publication.candidateRound(current, target),
                                target,
                                (s, r, ids) ->
                                        supply(
                                                s,
                                                r,
                                                "REGENERATE",
                                                scopeId("regenerate", key),
                                                ids));
                    else fail("INVALID_OPERATION");
                    var after = sessions.refresh(current);
                    if (Set.of("CARD", "BATCH").contains(operation)) {
                        var activeRound = discovery.latestRound(after).orElseThrow();
                        var view = json(publication.state(after, activeRound.id()));
                        var groups = new TreeMap<String, Set<String>>();
                        for (var slot : view.path("slots"))
                            if (slot.path("card")
                                    .path("long_review_state")
                                    .asText()
                                    .equals("NOT_STARTED")) {
                                String id = slot.path("card").path("candidate_id").asText();
                                String r = publication.candidateRound(after, id);
                                groups.computeIfAbsent(r, k -> new HashSet<>()).add(id);
                            }
                        for (var group : groups.entrySet()) {
                            autoLong(
                                    after,
                                    group.getKey(),
                                    group.getValue(),
                                    "visible:" + key,
                                    actionAt);
                            after = sessions.refresh(after);
                        }
                    }
                    subjects.renew(token, csrf, after);
                    after = sessions.refresh(after);
                    var latest = discovery.latestRound(after).orElseThrow();
                    var display = (ObjectNode) json(publication.displayState(after, latest.id()));
                    if (!operation.equals("UNDO"))
                        display.put("user_waiting", false).putNull("run_id");
                    queue.display(after, latest.id(), display.toString(), key);
                    String result =
                            isLong
                                    ? publication.longReview(
                                            after,
                                            publication.candidateRound(after, target),
                                            target)
                                    : state(token, session, false);
                    sessions.saveCommand(
                            after, "product:" + operation, key, hash, result, clock.instant());
                    return result;
                });
    }

    private void executionGuard(DispatchQueue.Context c) {
        var e = c.evidence();
        sessions.lockActive(e.scope());
        access.guard(e.scope(), e.round(), e.execution(), e.coordinatorBoot(), e.pythonBoot());
    }

    public String acceptPreferences(String execution, String body) {
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    var c = queue.context(execution);
                    executionGuard(c);
                    var scope = c.evidence().scope();
                    var round = discovery.roundInfo(scope, c.evidence().round());
                    var config = (ObjectNode) json(round.configuration());
                    var value =
                            json(
                                    preferences.resolve(
                                            preferenceContext(scope, execution, config), body));
                    config.put("preference_resolution", value.path("resolution_id").asText());
                    if (!value.path("has_positive_signal").asBoolean()) {
                        try {
                            config.set(
                                    "curated_content",
                                    JSON.valueToTree(
                                            curatedCandidates(
                                                    scope, round.id(), value.toString(), config)));
                            config.put("curated_pending", true);
                        } catch (PersistenceRejected unavailable) {
                            if (!unavailable.getMessage().equals("CURATED_UNAVAILABLE"))
                                throw unavailable;
                            publication.fail(scope, round.id(), "CURATED_UNAVAILABLE");
                            scope = sessions.refresh(scope);
                        }
                    }
                    discovery.configureRound(scope, round.id(), config.toString());
                    return value.toString();
                });
    }

    private Set<String> strings(JsonNode value) {
        var result = new HashSet<String>();
        value.forEach(n -> result.add(n.isTextual() ? n.asText() : n.path("id").asText()));
        return result;
    }

    public String acceptCandidates(String execution, String body) {
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    var c = queue.context(execution);
                    executionGuard(c);
                    var scope = c.evidence().scope();
                    var round = discovery.roundInfo(scope, c.evidence().round());
                    var config = json(round.configuration());
                    var pref = effective(scope, config);
                    if (pref.isNull()) fail("PREFERENCE_UNAVAILABLE");
                    var start = json(queue.startPayload(execution));
                    var excluded = new HashSet<UUID>();
                    for (var row : start.path("input").path("exclusions"))
                        excluded.add(UUID.fromString(row.path("release_group_id").asText()));
                    var positive = strings(pref.path("tags"));
                    for (var override : pref.path("overrides"))
                        if (Set.of("PREFER", "REQUIRE").contains(override.path("action").asText()))
                            positive.add(override.path("genre_id").asText());
                    var ctx =
                            new CandidateValidation.Context(
                                    config.path("catalog_dataset").asLong(),
                                    population(config),
                                    start.path("agent_policy")
                                            .path("audience_min_listeners")
                                            .asInt(),
                                    start.path("agent_policy")
                                            .path("audience_max_listeners")
                                            .asLong(Long.MAX_VALUE),
                                    start.path("recall_binding"),
                                    pref.path("input_digest").asText(),
                                    pref.path("effective_preference_digest").asText(),
                                    start.path("input").path("exclusion_digest").asText(),
                                    start.path("selection_config").path("revision").asText(),
                                    start.path("agent_policy").path("revision").asText(),
                                    excluded,
                                    strings(pref.path("required_tags")),
                                    strings(pref.path("forbidden_tags")),
                                    strings(pref.path("disliked_tags")),
                                    positive);
                    var proposal = parse(body, "RecallProposal");
                    var bundle = candidates.validate(scopeId("bundle", execution), proposal, ctx);
                    var map = new HashMap<String, String>();
                    bundle.path("accepted")
                            .forEach(
                                    n ->
                                            map.put(
                                                    n.path("release_group_id").asText(),
                                                    n.path("release_group_id").asText()));
                    queue.bindCandidates(execution, map);
                    c = queue.context(execution);
                    publication.register(
                            new PublicationAuthority.Context(
                                    c,
                                    config.path("preference_resolution").asText(),
                                    population(config),
                                    start.path("agent_policy").toString(),
                                    start.path("selection_config")
                                            .path("recommendation_target_count")
                                            .asInt(),
                                    "en".equals(start.path("input").path("language").asText())),
                            bundle.toString());
                    return bundle.toString();
                });
    }

    public String deliver(String execution, String body) {
        String deliveryResult =
                tx.write(
                        () -> {
                            discovery.lockRuntime();
                            var c = queue.context(execution);
                            var round =
                                    discovery.roundInfo(c.evidence().scope(), c.evidence().round());
                            var config = (ObjectNode) json(round.configuration());
                            var message = parse(body, "Delivery");
                            var start = json(queue.startPayload(execution));
                            boolean handoff =
                                    config.path("curated_pending").asBoolean()
                                            && message.path("kind").asText().equals("ROUND_REPORT")
                                            && message.path("payload")
                                                    .path("trigger_reason")
                                                    .asText()
                                                    .equals("JAVA_CURATED");
                            if (handoff)
                                queue.complete(c.evidence().scope(), c.budget().run(), "DEGRADED");
                            String result = publication.deliver(publication.context(c), body);
                            var published = json(result).path("published_candidate_ids");
                            if (!published.isEmpty()) {
                                var ids = strings(published);
                                autoLong(
                                        sessions.refresh(c.evidence().scope()),
                                        round.id(),
                                        ids,
                                        "published:" + message.path("delivery_id").asText(),
                                        Instant.parse(
                                                        start.path("deadlines")
                                                                .path("round_deadline_at")
                                                                .asText())
                                                .minusSeconds(limits.executionSeconds()));
                            }
                            if (handoff) {
                                var scope = sessions.refresh(c.evidence().scope());
                                config.put("curated_pending", false);
                                discovery.configureRound(scope, round.id(), config.toString());
                                var fixed =
                                        convert(
                                                config.path("curated_content"),
                                                CuratedRecommendations.Batch.class);
                                config.remove("curated_content");
                                config.put("curated_prepared", true)
                                        .put("curated_revision", fixed.revision());
                                discovery.configureRound(scope, round.id(), config.toString());
                                publication.publishCurated(
                                        scope,
                                        round.id(),
                                        c.budget().run(),
                                        config.path("settings")
                                                .path("selection_config")
                                                .path("recommendation_target_count")
                                                .asInt(),
                                        fixed);
                            }
                            return result;
                        });
        if (json(body).path("kind").asText().equals("ROUND_REPORT")) {
            tx.write(
                    () -> {
                        discovery.lockRuntime();
                        var c = queue.context(execution);
                        var scope = sessions.refresh(c.evidence().scope());
                        var round = discovery.roundInfo(scope, c.evidence().round());
                        var start = json(queue.startPayload(execution));
                        var state = json(publication.state(scope, round.id()));
                        boolean empty =
                                java.util.stream.StreamSupport.stream(
                                                state.path("slots").spliterator(), false)
                                        .anyMatch(
                                                slot ->
                                                        slot.path("status")
                                                                .asText()
                                                                .equals("EMPTY"));
                        if (start.path("start_mode").asText().equals("CONTINUE")
                                && start.path("run_kind").asText().equals("SUPPLY")
                                && discovery
                                        .latestRound(scope)
                                        .orElseThrow()
                                        .id()
                                        .equals(round.id())
                                && state.path("run_id").asText().equals(c.budget().run())
                                && state.path("supply_state").asText().equals("R_EXHAUSTED")
                                && json(body)
                                        .path("payload")
                                        .path("close_reason")
                                        .asText()
                                        .equals("DRAINED")
                                && empty) {
                            publication.operate(
                                    scope,
                                    round.id(),
                                    scopeId("drained", json(body).path("delivery_id").asText()),
                                    "SUPPLY",
                                    null,
                                    (s, r, slots) ->
                                            supply(
                                                    s,
                                                    r,
                                                    "SUPPLY",
                                                    scopeId(
                                                            "next-round",
                                                            json(body)
                                                                    .path("delivery_id")
                                                                    .asText()),
                                                    slots));
                        }
                        return null;
                    });
        }
        return deliveryResult;
    }

    public String prepareExecution(Scope scope, String text, Set<String> targets) {
        var start = (ObjectNode) json(text);
        String round = start.path("round_id").asText();
        var excludes = exclusions(scope, round);
        ((ObjectNode) start.path("input")).set("exclusions", excludes);
        ((ObjectNode) start.path("input")).put("exclusion_digest", digest(excludes));
        if (start.path("start_mode").asText().equals("CONTINUE")) {
            String operation =
                    start.path("run_kind").asText().equals("RERUN")
                            ? "REGENERATE"
                            : start.path("run_kind").asText();
            var continuation =
                    (ObjectNode)
                            json(
                                    publication.continuation(
                                            scope, round, operation, targets, digest(excludes)));
            if (continuation.path("display_state").path("user_waiting").asBoolean())
                ((ObjectNode) continuation.path("display_state"))
                        .put("run_id", start.path("run_id").asText());
            if (!continuation.path("pending").path("rescreen_run_id").isNull())
                ((ObjectNode) continuation.path("pending"))
                        .put("rescreen_run_id", start.path("run_id").asText());
            start.set("continuation", continuation);
            publication.bindRun(
                    scope,
                    round,
                    start.path("run_id").asText(),
                    continuation.path("candidate_bundle").toString());
        }
        return start.toString();
    }

    private void autoLong(
            Scope scope, String round, Set<String> targets, String key, Instant accepted) {
        publication.scheduleLongs(
                scope,
                round,
                targets,
                (s, r, ids) -> {
                    if (!queue.canAccept(s, limits)) return null;
                    var info = discovery.roundInfo(s, r);
                    var config = (ObjectNode) json(info.configuration());
                    String command =
                            UUID.nameUUIDFromBytes(
                                            key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .toString();
                    return enqueue(s, info, config, "REGENERATE", command, ids, true, accepted)
                            .run();
                });
    }

    private record Reuse(String cached, boolean eligible) {}

    private Reuse reuse(
            String token,
            String key,
            JsonNode request,
            Input input,
            JsonNode settings,
            long dataset) {
        if (!request.path("expected_session_id").isTextual()) return new Reuse(null, false);
        return tx.write(
                () -> {
                    var identity =
                            subjects.authenticate(token)
                                    .orElseThrow(() -> new PersistenceRejected("EXPIRED"));
                    var existing = sessions.current(identity.subjectId());
                    if (existing.isEmpty()
                            || !existing.get()
                                    .scope()
                                    .sessionId()
                                    .equals(request.path("expected_session_id").asText()))
                        return new Reuse(null, false);
                    var current = owned(token, request.path("expected_session_id").asText());
                    var prior = sessions.command(current, "submit-reuse", key, digest(request));
                    if (prior.isPresent()) return new Reuse(prior.get(), false);
                    var round = discovery.latestRound(current);
                    if (round.isEmpty()
                            || current.revision() != request.path("expected_revision").asLong())
                        return new Reuse(null, false);
                    var config = json(round.get().configuration());
                    var raw = json(sessions.current(current.subjectId()).orElseThrow().rawInput());
                    var state = json(publication.state(current, round.get().id()));
                    return new Reuse(
                            null,
                            config.path("catalog_dataset").asLong() == dataset
                                    && digest(config.path("settings")).equals(digest(settings))
                                    && digest(config.path("external_seeds"))
                                            .equals(digest(input.external()))
                                    && raw.path("input_digest")
                                            .equals(input.raw().path("input_digest"))
                                    && Set.of("SUCCEEDED", "PARTIAL", "NO_MATCH")
                                            .contains(state.path("status").asText()));
                });
    }
}
