package io.auralis.publication.adapters;

import static io.auralis.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.budget.api.BudgetAuthority;
import io.auralis.catalog.api.*;
import io.auralis.contracts.MessageDefinitions;
import io.auralis.discovery.api.*;
import io.auralis.evidence.api.EvidenceAuthority;
import io.auralis.persistence.*;
import io.auralis.preference.api.PreferenceStore;
import io.auralis.publication.api.*;
import io.auralis.session.api.SessionStore;
import io.auralis.session.api.SessionStore.Scope;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Atomic result admission and display consumption; external work is delegated after commit. */
@Service
public class PostgresPublicationAuthority implements PublicationAuthority {
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[[A-Za-z][0-9]+\\]");
    private static final Pattern ENGLISH_WORD =
            Pattern.compile("[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*");
    private final JdbcTemplate jdbc;
    private final ShortTransactions tx;
    private final SessionStore sessions;
    private final DiscoveryStore discovery;
    private final ExecutionReadAccess executions;
    private final DispatchQueue queue;
    private final BudgetAuthority budget;
    private final EvidenceAuthority evidence;
    private final PreferenceStore preferences;
    private final CatalogQuery catalog;
    private final AudienceQuery audience;
    private final PublicationStore store;
    private final RecoveryProjection projection;
    private final MessageDefinitions schema;
    private final Clock clock;

    public PostgresPublicationAuthority(
            JdbcTemplate jdbc,
            ShortTransactions tx,
            SessionStore sessions,
            DiscoveryStore discovery,
            ExecutionReadAccess executions,
            DispatchQueue queue,
            BudgetAuthority budget,
            EvidenceAuthority evidence,
            PreferenceStore preferences,
            CatalogQuery catalog,
            AudienceQuery audience,
            PublicationStore store,
            RecoveryProjection projection,
            MessageDefinitions schema,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sessions = sessions;
        this.discovery = discovery;
        this.executions = executions;
        this.queue = queue;
        this.budget = budget;
        this.evidence = evidence;
        this.preferences = preferences;
        this.catalog = catalog;
        this.audience = audience;
        this.store = store;
        this.projection = projection;
        this.schema = schema;
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

    private JsonNode parse(String text, String definition) {
        var n = json(text);
        if (!schema.validDefinition(definition, n)) fail("INVALID_PAYLOAD");
        return n;
    }

    private static boolean same(JsonNode a, JsonNode b) {
        return digest(a).equals(digest(b));
    }

    private Context current(Context c) {
        var e = c.execution().evidence();
        var scope = sessions.refresh(e.scope());
        var next =
                new EvidenceAuthority.Context(
                        scope,
                        e.round(),
                        e.execution(),
                        e.coordinatorBoot(),
                        e.pythonBoot(),
                        e.catalogDataset(),
                        e.readLimit(),
                        e.candidates(),
                        e.allowPrepared());
        var b = c.execution().budget();
        var nextBudget =
                new BudgetAuthority.Context(
                        next,
                        b.run(),
                        b.sessionScope(),
                        b.roundScope(),
                        b.sessionCap(),
                        b.roundCap(),
                        b.foregroundCap(),
                        b.backgroundCap(),
                        b.modelCalls(),
                        b.searchCalls(),
                        b.prices());
        return new Context(
                new DispatchQueue.Context(next, nextBudget),
                c.preferenceResolution(),
                c.audience(),
                c.policy(),
                c.target(),
                c.english());
    }

    private void guard(Context c) {
        var e = c.execution().evidence();
        sessions.lockActive(e.scope());
        executions.guard(e.scope(), e.round(), e.execution(), e.coordinatorBoot(), e.pythonBoot());
    }

    private Map<String, Object> round(Scope s, String r) {
        var rows =
                jdbc.queryForList(
                        "SELECT *,bundle::text AS bundle_text,policy::text AS policy_text FROM discovery.publication_round WHERE round_id=? AND session_id=?",
                        r,
                        s.sessionId());
        if (rows.size() != 1) fail("PUBLICATION_UNAVAILABLE");
        return rows.getFirst();
    }

    @Override
    public String begin(Scope s, String r, String run, int target, String status) {
        if (target < 1 || target > 1000 || !Set.of("WAITING", "RUNNING").contains(status))
            fail("INVALID_PAYLOAD");
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.lockActive(s);
                    if (jdbc.queryForObject(
                                    "SELECT count(*) FROM discovery.publication_round WHERE round_id=?",
                                    Long.class,
                                    r)
                            > 0) return state(s, r);
                    jdbc.update(
                            "INSERT INTO discovery.publication_round(session_id,round_id,run_id,target,status) VALUES(?,?,?,?,?)",
                            s.sessionId(),
                            r,
                            run,
                            target,
                            status);
                    for (int i = 1; i <= target; i++)
                        jdbc.update(
                                "INSERT INTO discovery.card_slot(session_id,slot_id,state) VALUES(?,?,'EMPTY') ON CONFLICT(session_id,slot_id) DO NOTHING",
                                s.sessionId(),
                                String.format("slot-%04d", i));
                    return saveState(sessions.advanceRevision(s), r);
                });
    }

    @Override
    public String publishCurated(
            Scope s, String r, String run, int target, CuratedRecommendations.Batch batch) {
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.lockActive(s);
                    begin(s, r, run, target, "RUNNING");
                    var current = sessions.refresh(s);
                    int rank = 0;
                    var seen = new HashSet<String>();
                    for (var item : batch.items()) {
                        if (!seen.add(item.id())) fail("DUPLICATE_CANDIDATE");
                        UUID id = UUID.fromString(item.id());
                        var identity = parse(item.identity(), "Identity");
                        var shortReview = parse(item.shortReview(), "ShortReview");
                        var longReview = parse(item.longReview(), "LongReview");
                        if (!longReview.path("candidate_id").asText().equals(item.id())
                                || !longReview.path("status").asText().equals("READY"))
                            fail("INVALID_PAYLOAD");
                        if (sessions.heardAlbums(current).contains(id)) continue;
                        store.addCandidate(
                                current,
                                new PublicationStore.Candidate(
                                        r,
                                        item.id(),
                                        id,
                                        ++rank,
                                        identity.toString(),
                                        "{\"policy\":\"CURATED_EXEMPT\"}",
                                        "ELIGIBLE"));
                        var card =
                                JSON.createObjectNode()
                                        .put("candidate_id", item.id())
                                        .put("selection_basis", "CURATED");
                        card.set("short_review", shortReview);
                        card.set("prepared_provenance", json(item.provenance()));
                        store.saveCard(current, r, item.id(), card.toString(), "CURATED", "READY");
                        store.putLongReview(
                                current,
                                r,
                                item.id(),
                                run,
                                "READY",
                                longReview.toString(),
                                null,
                                -1);
                        jdbc.update(
                                "UPDATE discovery.round_candidate SET display_rank=? WHERE round_id=? AND candidate_id=?",
                                rank,
                                r,
                                item.id());
                    }
                    fill(current, r, JSON.createArrayNode());
                    long count =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND state='READY'",
                                    Long.class,
                                    current.sessionId());
                    var diagnostic = JSON.createObjectNode().put("revision", batch.revision());
                    diagnostic.set("decisions", json(batch.diagnostics()));
                    jdbc.update(
                            "UPDATE discovery.publication_round SET status=?,analysis_mode='CURATED',supply_state=?,reason=?,policy=?::jsonb WHERE round_id=?",
                            count == 0 ? "NO_MATCH" : count < target ? "PARTIAL" : "SUCCEEDED",
                            ready(current, r).isEmpty() ? "FINAL_EXHAUSTED" : "SUFFICIENT",
                            count < target ? "recommendation.final_exhausted" : null,
                            diagnostic.toString(),
                            r);
                    return saveState(sessions.advanceRevision(current), r);
                });
    }

    @Override
    public void register(Context original, String body) {
        var bundle = parse(body, "CandidateBundle");
        if (!bundle.path("digest").asText().equals(digest(bundle.path("accepted")))
                || !bundle.path("binding")
                        .path("candidate_bundle_digest")
                        .asText()
                        .equals(bundle.path("digest").asText())) fail("BINDING_MISMATCH");
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    var c = current(original);
                    guard(c);
                    var s = c.execution().evidence().scope();
                    var r = c.execution().evidence().round();
                    var preference =
                            parse(
                                    preferences
                                            .find(s, c.preferenceResolution())
                                            .orElseThrow(
                                                    () ->
                                                            new PersistenceRejected(
                                                                    "PREFERENCE_UNAVAILABLE")),
                                    "EffectivePreferenceSnapshot");
                    if (!preference
                            .path("effective_preference_digest")
                            .equals(bundle.path("binding").path("effective_preference_digest")))
                        fail("BINDING_MISMATCH");
                    var row = round(s, r);
                    if (row.get("bundle_text") != null) {
                        if (!same(json((String) row.get("bundle_text")), bundle))
                            fail("IDEMPOTENCY_CONFLICT");
                        saveRunBinding(s, r, c.execution().budget().run(), bundle.path("binding"));
                        return null;
                    }
                    var requested = new ArrayList<UUID>();
                    for (var candidate : bundle.path("accepted"))
                        requested.add(UUID.fromString(candidate.path("release_group_id").asText()));
                    var albums =
                            catalog.albums(c.execution().evidence().catalogDataset(), requested);
                    var counts =
                            audience.audience(
                                    c.execution().evidence().catalogDataset(),
                                    c.audience(),
                                    requested);
                    var ids = new HashSet<String>();
                    for (var candidate : bundle.path("accepted")) {
                        String id = candidate.path("release_group_id").asText();
                        var uuid = UUID.fromString(id);
                        if (!ids.add(id)
                                || !identityValid(c, candidate, albums.get(uuid), counts.get(uuid)))
                            fail("ADMISSION_UNVERIFIED");
                        store.addCandidate(
                                s,
                                new PublicationStore.Candidate(
                                        r,
                                        id,
                                        UUID.fromString(id),
                                        candidate.path("recall_rank").asInt(),
                                        candidate.path("identity").toString(),
                                        candidate.path("audience").toString(),
                                        "DEFERRED"));
                    }
                    jdbc.update(
                            "UPDATE discovery.publication_round SET bundle=?::jsonb,policy=?::jsonb,preference_resolution=?,audience_binding=?::jsonb WHERE round_id=?",
                            body,
                            c.policy(),
                            c.preferenceResolution(),
                            JSON.valueToTree(c.audience()).toString(),
                            r);
                    saveRunBinding(s, r, c.execution().budget().run(), bundle.path("binding"));
                    return null;
                });
    }

    private boolean identityValid(Context c, JsonNode candidate) {
        UUID id;
        try {
            id = UUID.fromString(candidate.path("release_group_id").asText());
        } catch (IllegalArgumentException e) {
            return false;
        }
        var album = catalog.albums(c.execution().evidence().catalogDataset(), List.of(id)).get(id);
        Long listeners =
                audience.audience(
                                c.execution().evidence().catalogDataset(),
                                c.audience(),
                                List.of(id))
                        .get(id);
        return identityValid(c, candidate, album, listeners);
    }

    private boolean identityValid(
            Context c, JsonNode candidate, CatalogQuery.Album album, Long listeners) {
        var id = UUID.fromString(candidate.path("release_group_id").asText());
        if (album == null || !album.id().equals(id)) return false;
        var identity =
                JSON.createObjectNode()
                        .put("title", album.title())
                        .put("artist_credit", album.artistCredit());
        if (album.year() == null) identity.putNull("first_release_year");
        else identity.put("first_release_year", album.year());
        if (album.primaryType() == null) identity.putNull("release_type");
        else identity.put("release_type", album.primaryType());
        var genres = identity.putArray("primary_genres");
        album.genres()
                .forEach(
                        g -> genres.addObject().put("genre_id", g.genreId()).put("name", g.name()));
        var a = candidate.path("audience");
        var policy = json(c.policy());
        return same(identity, candidate.path("identity"))
                && listeners != null
                && policy.has("audience_min_listeners")
                && listeners >= policy.path("audience_min_listeners").asInt()
                && listeners <= policy.path("audience_max_listeners").asLong(Long.MAX_VALUE)
                && a.path("listeners").asLong() == listeners
                && a.path("dataset_id").asText().equals(c.audience().dataset())
                && a.path("window").asText().equals(c.audience().window())
                && a.path("sample_listeners").asLong() == c.audience().sampleListeners();
    }

    private boolean invocation(Context c, JsonNode id) {
        return id.isTextual() && budget.completed(c.execution().budget(), id.asText());
    }

    private boolean candidateRead(Context c, String read, String candidate) {
        return evidence.usableRead(c.execution().evidence(), read, candidate, false);
    }

    private boolean anyRead(Context c, String read, String candidate) {
        if (candidateRead(c, read, candidate)) return true;
        var raw =
                json(
                        sessions.current(c.execution().evidence().scope().subjectId())
                                .orElseThrow()
                                .rawInput());
        for (var seed : raw.path("liked_albums"))
            if (seed.path("release_group_id").isTextual()
                    && evidence.usableRead(
                            c.execution().evidence(),
                            read,
                            seed.path("release_group_id").asText(),
                            true)) return true;
        return false;
    }

    private String cardFailure(Context c, JsonNode batch, JsonNode card, JsonNode candidate) {
        String id = card.path("candidate_id").asText();
        var s = c.execution().evidence().scope();
        String r = c.execution().evidence().round();
        if (candidate == null) return "NOT_IN_BUNDLE";
        if (!identityValid(c, candidate)) return "ADMISSION_UNVERIFIED";
        if (sessions.heardAlbums(s).contains(UUID.fromString(id))
                || jdbc.queryForObject(
                                "SELECT count(*) FROM discovery.round_candidate WHERE session_id=? AND candidate_id=? AND shown AND round_id<>?",
                                Long.class,
                                s.sessionId(),
                                id,
                                r)
                        > 0) return "EXCLUDED";
        var screens =
                jdbc.queryForList(
                        "SELECT screening::text FROM discovery.round_candidate WHERE round_id=? AND candidate_id=?",
                        String.class,
                        r,
                        id);
        var screen =
                screens.isEmpty() || screens.getFirst() == null ? null : json(screens.getFirst());
        var admission = card.path("admission");
        var review = card.path("short_review");
        if (screen == null
                || !screen.path("screen").path("status").asText().equals("OK")
                || screen.path("screen").path("score").asInt()
                        != admission.path("screen_score").asInt()
                || !screen.path("screen")
                        .path("invocation_id")
                        .equals(admission.path("screen_invocation_id")))
            return "ADMISSION_UNVERIFIED";
        if (!budget.completed(
                        c.execution().budget(),
                        admission.path("screen_invocation_id").asText(),
                        Set.of("SCREEN", "SELECT", "STRUCTURE_REPAIR"))
                || !budget.completed(
                        c.execution().budget(),
                        review.path("invocation_id").asText(),
                        Set.of("WRITE_SHORT", "SELECT", "STRUCTURE_REPAIR")))
            return "ADMISSION_UNVERIFIED";
        var policy = parse(c.policy(), "AgentPolicy");
        int minimum =
                policy.path("screening")
                        .path(
                                batch.path("batch_kind").asText().equals("SUPPLY")
                                        ? "supplement_min_score"
                                        : "shortlist_min_score")
                        .asInt();
        if (!batch.path("analysis_mode").asText().equals("CURATED")
                && admission.path("screen_score").asInt() < minimum) return "SCORE_BELOW_THRESHOLD";
        var qualified = new HashSet<String>();
        screen.path("qualified_read_ids").forEach(n -> qualified.add(n.asText()));
        var used = new HashSet<String>();
        for (var read : admission.path("qualified_read_ids")) {
            if (!qualified.contains(read.asText()) || !candidateRead(c, read.asText(), id))
                return "READ_UNKNOWN";
            used.add(read.asText());
        }
        for (var citation : review.path("citations"))
            if (!used.contains(citation.path("read_id").asText())) return "CITATION_INVALID";
        String text = review.path("text").asText();
        if (c.english()) {
            long words =
                    ENGLISH_WORD
                            .matcher(CITATION_MARKER.matcher(text).replaceAll(""))
                            .results()
                            .count();
            if (words < 30 || words > 110) return "LENGTH_INVALID";
        } else {
            long han =
                    text.codePoints()
                            .filter(
                                    n ->
                                            (n >= 0x3400 && n <= 0x4dbf)
                                                    || (n >= 0x4e00 && n <= 0x9fff))
                            .count();
            if (han < policy.path("writing").path("short_min_han").asInt()
                    || han > policy.path("writing").path("short_max_han").asInt())
                return "LENGTH_INVALID";
        }
        String expected =
                batch.path("batch_kind").asText().equals("PROGRESSIVE")
                        ? "INDIVIDUAL"
                        : batch.path("analysis_mode").asText().equals("PERSONALIZED")
                                ? "SELECTED"
                                : batch.path("analysis_mode").asText().equals("CURATED")
                                        ? "CURATED"
                                        : "SCREEN_ONLY";
        return expected.equals(card.path("selection_basis").asText()) ? null : "BINDING_MISMATCH";
    }

    private List<String> ready(Scope s, String r) {
        var heard = sessions.heardAlbums(s);
        return jdbc
                .queryForList(
                        "SELECT c.candidate_id FROM discovery.round_candidate c JOIN discovery.recommendation_card k ON k.round_id=c.round_id AND k.candidate_id=c.candidate_id WHERE c.session_id=? AND c.round_id=? AND NOT c.shown AND NOT c.heard AND c.disposition<>'INELIGIBLE' AND k.state='READY' ORDER BY CASE WHEN k.selection_basis='BASIC' THEN 1 ELSE 0 END,c.display_rank,c.rank,c.candidate_id",
                        String.class,
                        s.sessionId(),
                        r)
                .stream()
                .filter(id -> !heard.contains(UUID.fromString(id)))
                .toList();
    }

    private void fill(Scope s, String r, com.fasterxml.jackson.databind.node.ArrayNode published) {
        var remaining = new ArrayDeque<>(ready(s, r));
        for (var slot :
                jdbc.queryForList(
                        "SELECT slot_id,revision FROM discovery.card_slot WHERE session_id=? AND state IN ('EMPTY','SUPPLYING') ORDER BY slot_id",
                        s.sessionId())) {
            if (remaining.isEmpty()) break;
            String id = remaining.removeFirst();
            store.putSlot(
                    s,
                    (String) slot.get("slot_id"),
                    r,
                    id,
                    "READY",
                    ((Number) slot.get("revision")).longValue());
            jdbc.update(
                    "UPDATE discovery.round_candidate SET shown=true WHERE round_id=? AND candidate_id=?",
                    r,
                    id);
            published.add(id);
        }
    }

    private ObjectNode display(Scope s, String r) {
        return display(s, r, null);
    }

    private ObjectNode display(Scope s, String r, String observerRun) {
        var info = round(s, r);
        var out =
                JSON.createObjectNode()
                        .put("display_seq", ((Number) info.get("display_seq")).longValue());
        if (info.get("progressive_tail_claim") instanceof String tail
                && "SUPPLYING".equals(info.get("supply_state"))
                && (observerRun == null || observerRun.equals(info.get("run_id"))))
            out.putObject("progressive_tail")
                    .put("candidate_id", tail)
                    .put("run_id", (String) info.get("run_id"));
        var shown = out.putArray("shown_candidate_ids");
        var heard = out.putArray("heard_candidate_ids");
        for (var row :
                jdbc.queryForList(
                        "SELECT candidate_id,shown,heard FROM discovery.round_candidate WHERE round_id=? ORDER BY rank,candidate_id",
                        r)) {
            if ((Boolean) row.get("shown")) shown.add((String) row.get("candidate_id"));
            if ((Boolean) row.get("heard")) heard.add((String) row.get("candidate_id"));
        }
        var remaining = out.putArray("ready_unshown_ids");
        ready(s, r).forEach(remaining::add);
        boolean waiting =
                jdbc.queryForObject(
                                "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND state='SUPPLYING'",
                                Long.class,
                                s.sessionId())
                        > 0;
        waiting =
                waiting
                        && discovery
                                .latestRound(s)
                                .map(latest -> latest.id().equals(r))
                                .orElse(false)
                        && (observerRun == null || observerRun.equals(round(s, r).get("run_id")));
        out.put("user_waiting", waiting);
        if (waiting) out.put("run_id", (String) round(s, r).get("run_id"));
        else out.putNull("run_id");
        return out;
    }

    private String saveState(Scope s, String r) {
        String state = render(s, discovery.latestRound(s).map(DiscoveryStore.Round::id).orElse(r));
        projection.save(s, state);
        return state;
    }

    @Override
    public String state(Scope s, String r) {
        return tx.write(
                () -> {
                    sessions.lockActive(s);
                    return render(s, r);
                });
    }

    private String render(Scope s, String r) {
        var info = round(s, r);
        var admission = queue.status(s, (String) info.get("run_id"));
        String status = (String) info.get("status");
        boolean interrupted =
                Set.of("CANCELLED", "INTERRUPTED", "EXPIRED", "SUPERSEDED", "FAILED")
                        .contains(admission.status());
        // A successful card batch can precede background completion. Only reconcile a
        // stranded supply indicator once the successful run's physical work ended.
        if (admission.status().equals("SUCCEEDED")
                && "SUPPLYING".equals(info.get("supply_state"))
                && !discovery.physicalWork(s, (String) info.get("run_id"))) interrupted = true;
        if (admission.status().equals("WAITING")) status = "WAITING";
        else if (Set.of("WAITING", "RUNNING").contains(status))
            status =
                    admission.status().equals("WAITING")
                            ? "WAITING"
                            : Set.of("CANCELLED", "INTERRUPTED", "EXPIRED", "SUPERSEDED")
                                            .contains(admission.status())
                                    ? "INTERRUPTED"
                                    : admission.status().equals("FAILED") ? "FAILED" : "RUNNING";
        String supply = (String) info.get("supply_state"), reason = (String) info.get("reason");
        if (interrupted && supply.equals("SUPPLYING")) {
            supply = "PAUSED";
            reason =
                    admission.errorCode() == null ? "EXECUTION_INTERRUPTED" : admission.errorCode();
            status =
                    jdbc.queryForObject(
                                            "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND state='READY'",
                                            Long.class,
                                            s.sessionId())
                                    > 0
                            ? "PARTIAL"
                            : "FAILED";
        }
        var out =
                JSON.createObjectNode()
                        .put("session_id", s.sessionId())
                        .put("round_id", r)
                        .put("run_id", (String) info.get("run_id"))
                        .put("revision", s.revision())
                        .put("status", status)
                        .put("analysis_mode", (String) info.get("analysis_mode"))
                        .put(
                                "recommendation_target_count",
                                ((Number) info.get("target")).intValue())
                        .put("supply_state", supply)
                        .put("message_key", reason)
                        .putNull("editable_input")
                        .put(
                                "expires_at",
                                sessions.current(s.subjectId())
                                        .orElseThrow()
                                        .expiresAt()
                                        .toString())
                        .put("restored", false);
        out.set("input_issues", json(info.get("input_issues").toString()));
        out.putObject("admission")
                .put("intent_id", admission.run())
                .put("status", admission.status())
                .put("accepted_at", admission.acceptedAt().toString())
                .put("queue_deadline", admission.queueDeadline().toString())
                .put("input_revision", sessions.inputRevision(s));
        var slots = out.putArray("slots");
        for (var row :
                jdbc.queryForList(
                        "SELECT s.slot_id,s.state,s.round_id,s.candidate_id,c.release_group_id AS album_id,k.content::text AS card,c.identity_binding::text AS identity,l.state AS long_state,l.generation_run AS long_run FROM discovery.card_slot s LEFT JOIN discovery.recommendation_card k ON k.round_id=s.round_id AND k.candidate_id=s.candidate_id LEFT JOIN discovery.round_candidate c ON c.round_id=s.round_id AND c.candidate_id=s.candidate_id LEFT JOIN discovery.long_review l ON l.round_id=s.round_id AND l.candidate_id=s.candidate_id WHERE s.session_id=? ORDER BY s.slot_id",
                        s.sessionId())) {
            var slot =
                    slots.addObject()
                            .put("slot_id", (String) row.get("slot_id"))
                            .put(
                                    "status",
                                    interrupted && row.get("state").equals("SUPPLYING")
                                            ? "FAILED"
                                            : (String) row.get("state"))
                            .putNull("message_key");
            if (row.get("card") == null) {
                slot.putNull("card");
                continue;
            }
            var card = json((String) row.get("card"));
            var product =
                    slot.putObject("card")
                            .put("candidate_id", (String) row.get("candidate_id"))
                            .put("cover_url", AlbumCovers.url((UUID) row.get("album_id"), 500))
                            .put(
                                    "long_review_state",
                                    row.get("long_state") == null
                                            ? "NOT_STARTED"
                                            : liveLongState(
                                                    s,
                                                    (String) row.get("long_run"),
                                                    (String) row.get("long_state")));
            product.set("identity", json((String) row.get("identity")));
            product.set("short_review", card.path("short_review"));
        }
        if (info.get("progressive_start_slot") instanceof String first) {
            String tail = batchTail(s, r, first);
            if (tail != null
                    && (supply.equals("SUFFICIENT")
                            || supply.equals("SUPPLYING")
                                    && ((Number) info.get("progressive_remaining")).intValue() > 0
                                    && !tail.equals(info.get("progressive_tail_claim"))))
                out.put("continuation_tail_id", tail);
        }
        if (!schema.validDefinition("ProductState", out)) fail("INVALID_PRODUCT_STATE");
        return out.toString();
    }

    @Override
    public String operate(
            Scope s, String r, String command, String operation, String slotId, Supply supply) {
        if (!Set.of("HEARD", "REPLACE", "BATCH", "SUPPLY").contains(operation))
            fail("INVALID_OPERATION");
        var request =
                JSON.createObjectNode()
                        .put("round", r)
                        .put("operation", operation)
                        .put("slot", slotId)
                        .put("revision", s.revision());
        String hash = digest(request);
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    var latest = sessions.refresh(s);
                    var previous = sessions.command(latest, operation, command, hash);
                    if (previous.isPresent()) return previous.get();
                    sessions.lockActive(s);
                    round(s, r);
                    var targets =
                            jdbc.queryForList(
                                    "SELECT slot_id,candidate_id,round_id,revision,state FROM discovery.card_slot WHERE session_id=? AND ((?='SUPPLY' AND state='EMPTY') OR ((?='BATCH' OR slot_id=?) AND (?<>'BATCH' OR state='READY'))) ORDER BY slot_id",
                                    s.sessionId(),
                                    operation,
                                    operation,
                                    slotId,
                                    operation);
                    if (targets.isEmpty()) fail("RESOURCE_UNAVAILABLE");
                    if (operation.equals("BATCH")) discardUndo(s);
                    if (Set.of("HEARD", "REPLACE").contains(operation)) {
                        var prior = targets.getFirst();
                        if (!prior.get("state").equals("READY")) fail("REVISION_CONFLICT");
                        String candidate = (String) prior.get("candidate_id");
                        boolean heardAdded =
                                operation.equals("HEARD")
                                        && !sessions.heardAlbums(s)
                                                .contains(UUID.fromString(candidate));
                        jdbc.update(
                                "INSERT INTO discovery.card_undo VALUES(?,?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET slot_id=excluded.slot_id,previous_round_id=excluded.previous_round_id,previous_candidate_id=excluded.previous_candidate_id,operation=excluded.operation,heard_added=excluded.heard_added",
                                s.sessionId(),
                                prior.get("slot_id"),
                                prior.get("round_id"),
                                candidate,
                                operation,
                                heardAdded);
                    }
                    var available = new ArrayDeque<>(ready(s, r));
                    var replacements = new LinkedHashMap<String, String>();
                    var empty = new TreeSet<String>();
                    for (var slot : targets) {
                        if (!slot.get("state")
                                .equals(operation.equals("SUPPLY") ? "EMPTY" : "READY"))
                            fail("REVISION_CONFLICT");
                        String id = (String) slot.get("candidate_id");
                        if (operation.equals("HEARD")) {
                            sessions.recordHeard(s, UUID.fromString(id), clock.instant());
                            jdbc.update(
                                    "UPDATE discovery.round_candidate SET heard=true WHERE round_id=? AND candidate_id=?",
                                    slot.get("round_id"),
                                    id);
                        }
                        String next = available.pollFirst();
                        replacements.put((String) slot.get("slot_id"), next);
                        if (next == null) empty.add((String) slot.get("slot_id"));
                    }
                    var updated = sessions.advanceRevision(s);
                    String displayRound = r;
                    boolean exhausted = false;
                    if (!empty.isEmpty()) {
                        String run = supply.enqueue(updated, r, Set.copyOf(empty));
                        if (run == null) {
                            exhausted = true;
                            jdbc.update(
                                    "UPDATE discovery.publication_round SET supply_state='FINAL_EXHAUSTED',reason='recommendation.final_exhausted' WHERE round_id=?",
                                    r);
                        } else {
                            String nextRound = discovery.runRound(updated, run);
                            if (!nextRound.equals(r)) {
                                begin(
                                        updated,
                                        nextRound,
                                        run,
                                        ((Number) round(updated, r).get("target")).intValue(),
                                        "WAITING");
                                updated = sessions.advanceRevision(sessions.refresh(updated));
                                displayRound = nextRound;
                            } else
                                jdbc.update(
                                        "UPDATE discovery.publication_round SET run_id=?,supply_state='SUPPLYING' WHERE round_id=?",
                                        run,
                                        r);
                        }
                    }
                    for (var slot : targets) {
                        String id = replacements.get((String) slot.get("slot_id"));
                        store.putSlot(
                                updated,
                                (String) slot.get("slot_id"),
                                id == null ? null : r,
                                id,
                                id == null ? (exhausted ? "EMPTY" : "SUPPLYING") : "READY",
                                ((Number) slot.get("revision")).longValue());
                        if (id != null)
                            jdbc.update(
                                    "UPDATE discovery.round_candidate SET shown=true WHERE round_id=? AND candidate_id=?",
                                    r,
                                    id);
                    }
                    if (exhausted) {
                        long count =
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND state='READY'",
                                        Long.class,
                                        updated.sessionId());
                        jdbc.update(
                                "UPDATE discovery.publication_round SET status=? WHERE round_id=?",
                                count == 0 ? "NO_MATCH" : "PARTIAL",
                                displayRound);
                    }
                    jdbc.update(
                            "UPDATE discovery.publication_round SET analysis_mode=? WHERE round_id=?",
                            displayMode(updated),
                            displayRound);
                    jdbc.update(
                            "UPDATE discovery.publication_round SET display_seq=display_seq+1 WHERE round_id=?",
                            displayRound);
                    String state = saveState(updated, displayRound);
                    sessions.saveCommand(updated, operation, command, hash, state, clock.instant());
                    return state;
                });
    }

    private int lastSlotNumber(Scope s) {
        return jdbc.queryForObject(
                "SELECT coalesce(max(substring(slot_id from 6)::integer),0) FROM discovery.card_slot WHERE session_id=?",
                Integer.class,
                s.sessionId());
    }

    private String batchTail(Scope s, String r, String firstSlot) {
        return jdbc
                .queryForList(
                        "SELECT candidate_id FROM discovery.card_slot WHERE session_id=? AND round_id=? AND slot_id>=? AND state='READY' ORDER BY slot_id DESC LIMIT 1",
                        String.class,
                        s.sessionId(),
                        r,
                        firstSlot)
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Override
    public void continueAfterTail(Scope s, String r, String tail, Supply supply) {
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.lockActive(s);
                    var current = round(s, r);
                    String first = (String) current.get("progressive_start_slot");
                    if (first == null || !tail.equals(batchTail(s, r, first))) return null;
                    if ("SUPPLYING".equals(current.get("supply_state"))) {
                        if (((Number) current.get("progressive_remaining")).intValue() > 0
                                && !tail.equals(current.get("progressive_tail_claim"))) {
                            jdbc.update(
                                    "UPDATE discovery.publication_round SET progressive_tail_claim=? WHERE round_id=?",
                                    tail,
                                    r);
                            saveState(sessions.advanceRevision(s), r);
                        }
                        return null;
                    }
                    if (!"SUFFICIENT".equals(current.get("supply_state"))) return null;
                    int last = lastSlotNumber(s);
                    int target = ((Number) current.get("target")).intValue();
                    var slots = new TreeSet<String>();
                    for (int i = 1; i <= target; i++)
                        slots.add(String.format("slot-%04d", last + i));
                    String run = supply.enqueue(s, r, slots);
                    if (run == null) return null;
                    for (String slot : slots)
                        jdbc.update(
                                "INSERT INTO discovery.card_slot(session_id,slot_id,state) VALUES(?,?,'SUPPLYING')",
                                s.sessionId(),
                                slot);
                    jdbc.update(
                            "UPDATE discovery.publication_round SET run_id=?,status='RUNNING',supply_state='SUPPLYING',reason=NULL,progressive_start_slot=?,progressive_remaining=0,progressive_tail_claim=NULL WHERE round_id=?",
                            run,
                            slots.getFirst(),
                            r);
                    saveState(sessions.advanceRevision(s), r);
                    return null;
                });
    }

    @Override
    public void discardUndo(Scope s) {
        sessions.lockActive(s);
        jdbc.update("DELETE FROM discovery.card_undo WHERE session_id=?", s.sessionId());
    }

    @Override
    public String undoLastCard(Scope s, String r) {
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.lockActive(s);
                    var records =
                            jdbc.queryForList(
                                    "SELECT * FROM discovery.card_undo WHERE session_id=?",
                                    s.sessionId());
                    if (records.isEmpty()) fail("RESOURCE_UNAVAILABLE");
                    var previous = records.getFirst();
                    String candidate = (String) previous.get("previous_candidate_id");
                    String previousRound = (String) previous.get("previous_round_id");
                    if ((Boolean) previous.get("heard_added")) {
                        sessions.forgetHeard(s, UUID.fromString(candidate));
                        jdbc.update(
                                "UPDATE discovery.round_candidate SET heard=false WHERE session_id=? AND round_id=? AND candidate_id=?",
                                s.sessionId(),
                                previousRound,
                                candidate);
                    }
                    var updated = sessions.advanceRevision(s);
                    long slotRevision =
                            jdbc.queryForObject(
                                    "SELECT revision FROM discovery.card_slot WHERE session_id=? AND slot_id=?",
                                    Long.class,
                                    s.sessionId(),
                                    previous.get("slot_id"));
                    store.putSlot(
                            updated,
                            (String) previous.get("slot_id"),
                            previousRound,
                            candidate,
                            "READY",
                            slotRevision);
                    discardUndo(updated);
                    long ready =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND state='READY'",
                                    Long.class,
                                    s.sessionId());
                    jdbc.update(
                            "UPDATE discovery.publication_round SET status=?,analysis_mode=?,reason=NULL,display_seq=display_seq+1 WHERE round_id=?",
                            ready >= ((Number) round(updated, r).get("target")).intValue()
                                    ? "SUCCEEDED"
                                    : "PARTIAL",
                            displayMode(updated),
                            r);
                    return saveState(updated, r);
                });
    }

    @Override
    public String longReview(Scope s, String r, String candidate) {
        return tx.write(
                () -> {
                    sessions.lockActive(s);
                    var rows =
                            jdbc.queryForList(
                                    "SELECT state,content::text,reason,generation_run FROM discovery.long_review WHERE session_id=? AND round_id=? AND candidate_id=?",
                                    s.sessionId(),
                                    r,
                                    candidate);
                    if (rows.size() != 1) fail("RESOURCE_UNAVAILABLE");
                    var row = rows.getFirst();
                    var out =
                            JSON.createObjectNode()
                                    .put("candidate_id", candidate)
                                    .put(
                                            "status",
                                            liveLongState(
                                                    s,
                                                    (String) row.get("generation_run"),
                                                    (String) row.get("state")))
                                    .put("message_key", (String) row.get("reason"));
                    out.set(
                            "review",
                            row.get("content") == null
                                    ? JSON.nullNode()
                                    : json((String) row.get("content")));
                    if ("NEEDS_MORE_RESEARCH".equals(row.get("state"))
                            && out.path("status").asText().equals("FAILED")
                            && "FAILED"
                                    .equals(
                                            jdbc.queryForObject(
                                                    "SELECT screening#>>'{screen,status}' FROM discovery.round_candidate WHERE round_id=? AND candidate_id=?",
                                                    String.class,
                                                    r,
                                                    candidate))) {
                        out.put("message_key", "RESEARCH_FAILED");
                    }
                    if (!schema.validDefinition("LongReviewView", out))
                        fail("INVALID_LONG_REVIEW_STATE");
                    return out.toString();
                });
    }

    @Override
    public String regenerate(Scope s, String r, String candidate, Supply supply) {
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.lockActive(s);
                    var rows =
                            jdbc.queryForList(
                                    "SELECT state,revision,generation_run FROM discovery.long_review WHERE session_id=? AND round_id=? AND candidate_id=?",
                                    s.sessionId(),
                                    r,
                                    candidate);
                    if (rows.size() != 1
                            || liveLongState(
                                            s,
                                            (String) rows.getFirst().get("generation_run"),
                                            (String) rows.getFirst().get("state"))
                                    .equals("GENERATING")) fail("REVISION_CONFLICT");
                    var updated = sessions.advanceRevision(s);
                    String run = supply.enqueue(updated, r, Set.of(candidate));
                    store.putLongReview(
                            updated,
                            r,
                            candidate,
                            run,
                            "GENERATING",
                            null,
                            null,
                            ((Number) rows.getFirst().get("revision")).longValue());
                    saveState(updated, r);
                    return longReview(updated, r, candidate);
                });
    }

    @Override
    public String bundle(Scope s, String r) {
        return tx.write(
                () -> {
                    sessions.lockActive(s);
                    var row = round(s, r);
                    if (row.get("bundle_text") == null) fail("BUNDLE_UNAVAILABLE");
                    return (String) row.get("bundle_text");
                });
    }

    @Override
    public String deliver(Context original, String body) {
        var delivery = parse(body, "Delivery");
        if (!delivery.path("payload_digest").asText().equals(digest(delivery.path("payload"))))
            fail("BINDING_MISMATCH");
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    var c = current(original);
                    var e = c.execution().evidence();
                    var s = e.scope();
                    sessions.lockActive(s);
                    String id = delivery.path("delivery_id").asText(),
                            kind = delivery.path("kind").asText(),
                            r = e.round();
                    if (!id.startsWith(e.execution() + ":")
                            || !delivery.path("round_id").asText().equals(r))
                        fail("BINDING_MISMATCH");
                    var old =
                            jdbc.queryForList(
                                    "SELECT payload_digest,result::text FROM discovery.delivery_receipt WHERE id=? AND session_id=? AND execution_id=?",
                                    id,
                                    s.sessionId(),
                                    e.execution());
                    if (!old.isEmpty()) {
                        if (!digest(delivery).equals(old.getFirst().get("payload_digest")))
                            fail("IDEMPOTENCY_CONFLICT");
                        return (String) old.getFirst().get("result");
                    }
                    guard(c);
                    var row = round(s, r);
                    boolean currentRound =
                            discovery
                                    .latestRound(s)
                                    .map(DiscoveryStore.Round::id)
                                    .orElse(r)
                                    .equals(r);
                    if (!currentRound && !delivery.path("run_id").isNull())
                        fail("BINDING_MISMATCH");
                    var bound =
                            row.get("bundle_text") == null
                                    ? null
                                    : json((String) row.get("bundle_text"));
                    if (row.get("policy_text") != null
                            && (!same(json((String) row.get("policy_text")), json(c.policy()))
                                    || ((Number) row.get("target")).intValue() != c.target()))
                        fail("BINDING_MISMATCH");
                    if (!delivery.path("binding").isNull()) {
                        if (bound == null
                                || !same(
                                        delivery.path("binding"),
                                        runBinding(
                                                s,
                                                r,
                                                c.execution().budget().run(),
                                                bound.path("binding")))) fail("BINDING_MISMATCH");
                        var pref =
                                parse(
                                        preferences
                                                .find(s, (String) row.get("preference_resolution"))
                                                .orElseThrow(
                                                        () ->
                                                                new PersistenceRejected(
                                                                        "PREFERENCE_UNAVAILABLE")),
                                        "EffectivePreferenceSnapshot");
                        if (!pref.path("effective_preference_digest")
                                .equals(bound.path("binding").path("effective_preference_digest")))
                            fail("BINDING_MISMATCH");
                    }
                    budget.state(c.execution().budget());
                    LockOrder.before(LockOrder.Level.DELIVERY, id);
                    var payload = delivery.path("payload");
                    boolean visibleChange =
                            currentRound
                                    && ((Set.of("CARD_BATCH", "ORDER_UPDATE").contains(kind)
                                                    && !delivery.path("run_id").isNull())
                                            || (kind.equals("ROUND_REPORT")
                                                    && row.get("run_id")
                                                            .equals(c.execution().budget().run())
                                                    && (Set.of("WAITING", "RUNNING")
                                                                    .contains(row.get("status"))
                                                            || jdbc.queryForObject(
                                                                            "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND state='SUPPLYING'",
                                                                            Long.class,
                                                                            s.sessionId())
                                                                    > 0)));
                    var items = JSON.createArrayNode();
                    var published = JSON.createArrayNode();
                    var candidates = new HashMap<String, JsonNode>();
                    if (bound != null)
                        bound.path("accepted")
                                .forEach(
                                        n ->
                                                candidates.put(
                                                        n.path("release_group_id").asText(), n));
                    if (kind.equals("SCREENINGS") || kind.equals("CARD_BATCH")) {
                        var screens =
                                kind.equals("SCREENINGS") ? payload : payload.path("screenings");
                        var seen = new HashSet<String>();
                        for (var screen : screens) {
                            String candidate = screen.path("candidate_id").asText();
                            if (!candidates.containsKey(candidate) || !seen.add(candidate))
                                fail("NOT_IN_BUNDLE");
                            jdbc.update(
                                    "UPDATE discovery.round_candidate SET screening=?::jsonb,disposition=? WHERE round_id=? AND candidate_id=?",
                                    screen.toString(),
                                    screen.path("disposition").asText(),
                                    r,
                                    candidate);
                        }
                    }
                    if (kind.equals("CARD_BATCH"))
                        publishBatch(c, delivery, row, candidates, items, published);
                    else if (kind.equals("LONG_REVIEW")) publishLong(c, payload);
                    else if (kind.equals("ORDER_UPDATE")) {
                        publishOrder(c, payload, row, candidates, published);
                    } else if (kind.equals("SUPPLY_STATUS"))
                        jdbc.update(
                                "UPDATE discovery.publication_round SET supply_state=?,reason=? WHERE round_id=?",
                                payload.path("state").asText(),
                                payload.path("reason_code").isNull()
                                        ? null
                                        : payload.path("reason_code").asText(),
                                r);
                    else if (kind.equals("ROUND_REPORT")) {
                        jdbc.update(
                                "UPDATE discovery.long_review SET state='INTERRUPTED',revision=revision+1 WHERE round_id=? AND generation_run=? AND state='GENERATING'",
                                r,
                                c.execution().budget().run());
                        if (currentRound
                                && row.get("run_id").equals(c.execution().budget().run())) {
                            boolean exhausted =
                                    "R_EXHAUSTED".equals(round(s, r).get("supply_state"))
                                            && payload.path("close_reason")
                                                    .asText()
                                                    .equals("DRAINED");
                            int unfinished =
                                    jdbc.update(
                                            "UPDATE discovery.card_slot SET state=?,revision=revision+1 WHERE session_id=? AND state='SUPPLYING'",
                                            exhausted ? "EMPTY" : "FAILED",
                                            s.sessionId());
                            if (unfinished > 0 && exhausted) {
                                long ready =
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND state='READY'",
                                                Long.class,
                                                s.sessionId());
                                jdbc.update(
                                        "UPDATE discovery.publication_round SET status=?,reason=NULL WHERE round_id=?",
                                        ready > 0 ? "PARTIAL" : "NO_MATCH",
                                        r);
                            }
                            jdbc.update(
                                    "UPDATE discovery.publication_round SET supply_state=CASE WHEN supply_state='SUPPLYING' THEN 'PAUSED' ELSE supply_state END WHERE round_id=?",
                                    r);
                            jdbc.update(
                                    "UPDATE discovery.publication_round SET status='INTERRUPTED',reason='execution.interrupted' WHERE round_id=? AND status IN ('WAITING','RUNNING')",
                                    r);
                        }
                    }
                    if (Set.of("CARD_BATCH", "ORDER_UPDATE").contains(kind))
                        jdbc.update(
                                "UPDATE discovery.publication_round SET display_seq=display_seq+1 WHERE round_id=?",
                                r);
                    var updated = visibleChange ? sessions.advanceRevision(s) : s;
                    boolean intermediate =
                            kind.equals("CARD_BATCH")
                                    && payload.path("batch_kind").asText().equals("PROGRESSIVE")
                                    && payload.path("pending")
                                            .path("supply_state")
                                            .asText()
                                            .equals("SUPPLYING");
                    if (((kind.equals("CARD_BATCH") || kind.equals("ORDER_UPDATE"))
                                    && !delivery.path("run_id").isNull()
                                    && !intermediate)
                            || kind.equals("ROUND_REPORT")) {
                        String outcome = (String) round(updated, r).get("status");
                        if (kind.equals("ROUND_REPORT")
                                && !round(updated, r)
                                        .get("run_id")
                                        .equals(c.execution().budget().run())) {
                            var longStates =
                                    jdbc.queryForList(
                                            "SELECT state FROM discovery.long_review WHERE round_id=? AND generation_run=?",
                                            String.class,
                                            r,
                                            c.execution().budget().run());
                            outcome =
                                    !longStates.isEmpty()
                                                    && longStates.stream().allMatch("READY"::equals)
                                            ? "SUCCEEDED"
                                            : "FAILED";
                        }
                        queue.complete(
                                updated,
                                c.execution().budget().run(),
                                outcome.equals("SUCCEEDED")
                                        ? ("BASIC".equals(round(updated, r).get("analysis_mode"))
                                                ? "DEGRADED"
                                                : "SUCCEEDED")
                                        : outcome.equals("PARTIAL")
                                                ? "DEGRADED"
                                                : outcome.equals("NO_MATCH")
                                                        ? "NO_MATCH"
                                                        : "FAILED");
                    }
                    if (visibleChange) saveState(updated, r);
                    var result =
                            JSON.createObjectNode()
                                    .put(
                                            "status",
                                            items.isEmpty() ? "ACCEPTED" : acceptedStatus(items));
                    result.set("items", items);
                    result.set("published_candidate_ids", published);
                    result.set("display_state", display(updated, r, c.execution().budget().run()));
                    if (!schema.validDefinition("DeliveryResult", result))
                        fail("INVALID_DELIVERY_RESULT");
                    long seq =
                            jdbc.queryForObject(
                                    "SELECT coalesce(max(seq),0)+1 FROM discovery.delivery_receipt WHERE execution_id=?",
                                    Long.class,
                                    e.execution());
                    store.saveDelivery(
                            updated,
                            id,
                            r,
                            e.execution(),
                            seq,
                            digest(delivery),
                            result.toString());
                    return result.toString();
                });
    }

    private String acceptedStatus(JsonNode items) {
        int accepted = 0;
        for (var n : items) if (n.path("decision").asText().equals("ACCEPTED")) accepted++;
        return accepted == 0
                ? "REJECTED"
                : accepted == items.size() ? "ACCEPTED" : "PARTIALLY_ACCEPTED";
    }

    private void publishBatch(
            Context c,
            JsonNode delivery,
            Map<String, Object> row,
            Map<String, JsonNode> candidates,
            com.fasterxml.jackson.databind.node.ArrayNode items,
            com.fasterxml.jackson.databind.node.ArrayNode published) {
        var e = c.execution().evidence();
        var s = e.scope();
        String r = e.round();
        var payload = delivery.path("payload");
        boolean publish =
                !delivery.path("run_id").isNull()
                        && delivery.path("run_id").asText().equals(row.get("run_id"));
        if (!delivery.path("run_id").isNull() && !publish) fail("BINDING_MISMATCH");
        boolean progressive = payload.path("batch_kind").asText().equals("PROGRESSIVE");
        String supply = payload.path("pending").path("supply_state").asText();
        if (progressive
                && (!publish
                        || !payload.path("analysis_mode").asText().equals("PERSONALIZED")
                        || !payload.path("selection").path("status").asText().equals("NOT_RUN")
                        || payload.path("cards").size() != (supply.equals("SUPPLYING") ? 1 : 0)))
            fail("INVALID_PAYLOAD");
        var accepted = new HashMap<String, JsonNode>();
        var seen = new HashSet<String>();
        for (var card : payload.path("cards")) {
            String candidate = card.path("candidate_id").asText();
            if (!seen.add(candidate)) fail("DUPLICATE_CANDIDATE");
            String reason = cardFailure(c, payload, card, candidates.get(candidate));
            items.addObject()
                    .put("candidate_id", candidate)
                    .put("decision", reason == null ? "ACCEPTED" : "REJECTED")
                    .put("reason", reason);
            if (reason == null) accepted.put(candidate, card);
        }
        var order = new ArrayList<String>();
        if (!progressive
                && payload.path("analysis_mode").asText().equals("PERSONALIZED")
                && !payload.path("cards").isEmpty()) {
            if (!payload.path("selection").path("status").asText().equals("COMPLETED")
                    || !budget.completed(
                            c.execution().budget(),
                            payload.path("selection").path("invocation_id").asText(),
                            Set.of("SELECT", "STRUCTURE_REPAIR"))) fail("SELECTION_UNVERIFIED");
            for (var chosen : payload.path("selection").path("order"))
                if (!chosen.path("verdict").asText().equals("reject"))
                    order.add(chosen.path("candidate_id").asText());
        } else payload.path("screen_order").forEach(n -> order.add(n.asText()));
        if (new HashSet<>(order).size() != order.size() || !order.containsAll(accepted.keySet()))
            fail("SELECTION_UNVERIFIED");
        int rank = 0;
        for (String candidate : order)
            if (accepted.containsKey(candidate)) {
                var card = accepted.get(candidate);
                var existing =
                        jdbc.queryForList(
                                "SELECT content::text FROM discovery.recommendation_card WHERE round_id=? AND candidate_id=?",
                                String.class,
                                r,
                                candidate);
                if (existing.isEmpty()) {
                    String basis =
                            Set.of("SELECTED", "INDIVIDUAL")
                                            .contains(card.path("selection_basis").asText())
                                    ? "PERSONALIZED"
                                    : card.path("selection_basis").asText().equals("CURATED")
                                            ? "CURATED"
                                            : "BASIC";
                    store.saveCard(s, r, candidate, card.toString(), basis, "READY");
                    store.putLongReview(
                            s,
                            r,
                            candidate,
                            c.execution().budget().run(),
                            card.path("long_review_state").asText(),
                            null,
                            null,
                            -1);
                } else if (!same(json(existing.getFirst()), card)) fail("IDEMPOTENCY_CONFLICT");
                jdbc.update(
                        "UPDATE discovery.round_candidate SET display_rank=? WHERE round_id=? AND candidate_id=?",
                        rank++,
                        r,
                        candidate);
            }
        if (!publish) return;
        if (progressive
                && !accepted.isEmpty()
                && !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM discovery.card_slot WHERE session_id=? AND state IN ('EMPTY','SUPPLYING'))",
                                Boolean.class,
                                s.sessionId())))
            jdbc.update(
                    "INSERT INTO discovery.card_slot(session_id,slot_id,state) VALUES(?,?,'SUPPLYING')",
                    s.sessionId(),
                    String.format("slot-%04d", lastSlotNumber(s) + 1));
        fill(s, r, published);
        if (progressive) {
            finishProgressiveDelivery(s, r, row, payload, supply);
            return;
        }
        long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND state='READY'",
                        Long.class,
                        s.sessionId());
        String outcome = payload.path("outcome").asText();
        String status =
                count > 0
                        ? (count < c.target() ? "PARTIAL" : "SUCCEEDED")
                        : outcome.equals("NO_MATCH") ? "NO_MATCH" : "FAILED";
        jdbc.update(
                "UPDATE discovery.publication_round SET status=?,analysis_mode=?,supply_state=?,reason=?,input_issues=?::jsonb WHERE round_id=?",
                status,
                displayMode(s),
                payload.path("pending").path("supply_state").asText(),
                payload.path("reason_code").isNull() ? null : payload.path("reason_code").asText(),
                payload.path("input_issues").toString(),
                r);
    }

    private void finishProgressiveDelivery(
            Scope s, String r, Map<String, Object> row, JsonNode payload, String supply) {
        String first =
                row.get("progressive_start_slot") == null
                        ? "slot-0001"
                        : (String) row.get("progressive_start_slot");
        boolean pending = supply.equals("SUPPLYING");
        if (!pending)
            jdbc.update(
                    "DELETE FROM discovery.card_slot WHERE session_id=? AND slot_id>=? AND state IN ('EMPTY','SUPPLYING')",
                    s.sessionId(),
                    first);
        long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.card_slot WHERE session_id=? AND round_id=? AND slot_id>=? AND state='READY'",
                        Long.class,
                        s.sessionId(),
                        r,
                        first);
        String status =
                pending
                        ? "RUNNING"
                        : count >= ((Number) row.get("target")).intValue()
                                ? "SUCCEEDED"
                                : count > 0
                                        ? "PARTIAL"
                                        : payload.path("outcome").asText().equals("NO_MATCH")
                                                ? "NO_MATCH"
                                                : "FAILED";
        if (supply.equals("SUFFICIENT")) {
            boolean remaining =
                    Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM discovery.round_candidate WHERE round_id=? AND NOT shown AND NOT heard AND disposition NOT IN ('INELIGIBLE','BELOW_FLOOR'))",
                                    Boolean.class,
                                    r));
            if (!remaining) supply = "R_EXHAUSTED";
            else if (count == 0) supply = "PAUSED";
        }
        jdbc.update(
                "UPDATE discovery.publication_round SET status=?,analysis_mode=?,supply_state=?,reason=?,progressive_start_slot=?,progressive_remaining=?,progressive_tail_claim=CASE WHEN ? THEN progressive_tail_claim ELSE NULL END WHERE round_id=?",
                status,
                displayMode(s),
                supply,
                payload.path("reason_code").asText(null),
                first,
                payload.path("pending").path("deferred_candidates").asInt(),
                pending,
                r);
    }

    private void publishLong(Context c, JsonNode payload) {
        var e = c.execution().evidence();
        var s = e.scope();
        String r = e.round(), candidate = payload.path("candidate_id").asText();
        var reviews =
                jdbc.queryForList(
                        "SELECT generation_run,revision,state,content::text FROM discovery.long_review WHERE session_id=? AND round_id=? AND candidate_id=?",
                        s.sessionId(),
                        r,
                        candidate);
        if (reviews.size() != 1
                || !reviews.getFirst().get("generation_run").equals(c.execution().budget().run()))
            fail("STALE_LONG_REVIEW");
        var old = reviews.getFirst();
        if (old.get("state").equals("READY")) {
            if (old.get("content") == null || !same(json((String) old.get("content")), payload))
                fail("IDEMPOTENCY_CONFLICT");
            return;
        }
        if (payload.path("status").asText().equals("READY")) {
            if (payload.path("sections").isNull()
                    || payload.path("citations").isEmpty()
                    || payload.path("invocation_ids").isEmpty()) fail("ADMISSION_UNVERIFIED");
            boolean written = false;
            for (var call : payload.path("invocation_ids"))
                written |=
                        budget.completed(
                                c.execution().budget(),
                                call.asText(),
                                Set.of("WRITE_LONG", "LONG_TOOL_TURN", "STRUCTURE_REPAIR"));
            if (!written) fail("ADMISSION_UNVERIFIED");
            for (var call : payload.path("invocation_ids"))
                if (!invocation(c, call)) fail("ADMISSION_UNVERIFIED");
            for (var citation : payload.path("citations"))
                if (!anyRead(c, citation.path("read_id").asText(), candidate))
                    fail("CITATION_INVALID");
        }
        store.putLongReview(
                s,
                r,
                candidate,
                c.execution().budget().run(),
                payload.path("status").asText(),
                payload.toString(),
                payload.path("reason_code").isNull() ? null : payload.path("reason_code").asText(),
                ((Number) old.get("revision")).longValue());
    }

    @Override
    public Context context(DispatchQueue.Context execution) {
        return tx.write(
                () -> {
                    var scope = sessions.refresh(execution.evidence().scope());
                    sessions.lockActive(scope);
                    var row = round(scope, execution.evidence().round());
                    AudienceQuery.Binding binding = null;
                    if (row.get("audience_binding") != null)
                        try {
                            binding =
                                    JSON.treeToValue(
                                            json(row.get("audience_binding").toString()),
                                            AudienceQuery.Binding.class);
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(e);
                        }
                    var start = json(queue.startPayload(execution.evidence().execution()));
                    return new Context(
                            execution,
                            (String) row.get("preference_resolution"),
                            binding,
                            start.path("agent_policy").toString(),
                            ((Number) row.get("target")).intValue(),
                            "en".equals(start.path("input").path("language").asText()));
                });
    }

    @Override
    public String exclusions(Scope s) {
        return exclusions(s, null);
    }

    public String exclusions(Scope s, String currentRound) {
        return tx.write(
                () -> {
                    sessions.lockActive(s);
                    var result = JSON.createArrayNode();
                    var seen = new HashSet<String>();
                    int sequence =
                            currentRound == null
                                    ? discovery
                                            .latestRound(s)
                                            .map(info -> info.sequence() + 1)
                                            .orElse(1)
                                    : discovery.roundInfo(s, currentRound).sequence();
                    for (var row :
                            jdbc.queryForList(
                                    "SELECT candidate_id,shown,round_id,screening->>'research_status' AS research FROM discovery.round_candidate WHERE session_id=? ORDER BY rank,candidate_id",
                                    s.sessionId())) {
                        String id = (String) row.get("candidate_id");
                        if (java.util.Objects.equals(currentRound, row.get("round_id"))
                                && !(Boolean) row.get("shown")) continue;
                        if (!(Boolean) row.get("shown")
                                && "READ_FAILED".equals(row.get("research"))
                                && discovery.roundInfo(s, (String) row.get("round_id")).sequence()
                                        < sequence - 1) continue;
                        if (seen.add(id))
                            result.addObject()
                                    .put("release_group_id", id)
                                    .put(
                                            "reason",
                                            (Boolean) row.get("shown")
                                                    ? "SHOWN"
                                                    : "PRIOR_ROUND_RECALL");
                    }
                    return result.toString();
                });
    }

    @Override
    public String continuation(
            Scope s, String r, String operation, Set<String> targets, String exclusionDigest) {
        return tx.write(
                () -> {
                    sessions.lockActive(s);
                    var bound = (ObjectNode) json(bundle(s, r));
                    ((ObjectNode) bound.path("binding")).put("exclusion_digest", exclusionDigest);
                    var out = JSON.createObjectNode();
                    out.set("candidate_bundle", bound);
                    var rows = out.putArray("candidates");
                    for (var row :
                            jdbc.queryForList(
                                    "SELECT c.candidate_id,c.rank,c.shown,c.disposition,c.screening::text,k.state AS card_state,l.state AS long_state FROM discovery.round_candidate c LEFT JOIN discovery.recommendation_card k ON k.round_id=c.round_id AND k.candidate_id=c.candidate_id LEFT JOIN discovery.long_review l ON l.round_id=c.round_id AND l.candidate_id=c.candidate_id WHERE c.session_id=? AND c.round_id=? ORDER BY c.rank,c.candidate_id",
                                    s.sessionId(),
                                    r)) {
                        String id = (String) row.get("candidate_id"),
                                state =
                                        (Boolean) row.get("shown")
                                                ? "SHOWN"
                                                : row.get("card_state") != null
                                                        ? "READY"
                                                        : Set.of(
                                                                                "INELIGIBLE",
                                                                                "BELOW_FLOOR",
                                                                                "RESERVE")
                                                                        .contains(
                                                                                row.get(
                                                                                        "disposition"))
                                                                ? (String) row.get("disposition")
                                                                : "UNRESEARCHED";
                        var item =
                                rows.addObject()
                                        .put("release_group_id", id)
                                        .put("recall_rank", ((Number) row.get("rank")).intValue())
                                        .put("state", state)
                                        .put(
                                                "long_review_state",
                                                row.get("long_state") == null
                                                        ? "NOT_STARTED"
                                                        : (String) row.get("long_state"));
                        item.set(
                                "screening",
                                row.get("screening") == null
                                        ? JSON.nullNode()
                                        : json((String) row.get("screening")));
                        item.set("read_refs", json(evidence.references(s, r, id)));
                    }
                    var display = display(s, r);
                    display.put("user_waiting", !operation.equals("REGENERATE"))
                            .put("run_id", operation.equals("REGENERATE") ? null : "pending-run");
                    out.set("display_state", display);
                    var pending =
                            out.putObject("pending")
                                    .put(
                                            "rescreen_run_id",
                                            operation.equals("RESCREEN") ? "pending-run" : null);
                    var regenerate = pending.putArray("regenerate_long_review_ids");
                    if (operation.equals("REGENERATE"))
                        targets.stream().sorted().forEach(regenerate::add);
                    pending.putArray("prioritized_long_review_ids");
                    if (!schema.validDefinition("Continuation", out)) fail("INVALID_CONTINUATION");
                    return out.toString();
                });
    }

    @Override
    public void bindRun(Scope s, String r, String run, String text) {
        var next = parse(text, "CandidateBundle");
        tx.write(
                () -> {
                    sessions.lockActive(s);
                    var old = json(bundle(s, r));
                    if (!same(old.path("accepted"), next.path("accepted")))
                        fail("BINDING_MISMATCH");
                    var binding = (ObjectNode) next.path("binding").deepCopy();
                    binding.set("exclusion_digest", old.path("binding").path("exclusion_digest"));
                    if (!same(old.path("binding"), binding)) fail("BINDING_MISMATCH");
                    jdbc.update(
                            "INSERT INTO discovery.publication_run_binding VALUES(?,?,?,?::jsonb) ON CONFLICT(run_id) DO NOTHING",
                            s.sessionId(),
                            r,
                            run,
                            next.path("binding").toString());
                    var stored =
                            jdbc.queryForObject(
                                    "SELECT binding::text FROM discovery.publication_run_binding WHERE run_id=?",
                                    String.class,
                                    run);
                    if (!same(json(stored), next.path("binding"))) fail("IDEMPOTENCY_CONFLICT");
                    jdbc.update(
                            "UPDATE discovery.publication_round SET bundle=?::jsonb WHERE round_id=?",
                            text,
                            r);
                    return null;
                });
    }

    @Override
    public String waitFor(Scope s, String r, String run) {
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.lockActive(s);
                    jdbc.update(
                            "UPDATE discovery.publication_round SET run_id=?,status='WAITING',supply_state='SUPPLYING',reason=NULL WHERE round_id=? AND session_id=?",
                            run,
                            r,
                            s.sessionId());
                    return saveState(sessions.advanceRevision(s), r);
                });
    }

    @Override
    public String fail(Scope s, String r, String reason) {
        return tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.lockActive(s);
                    var row = round(s, r);
                    jdbc.update(
                            "UPDATE discovery.publication_round SET status='FAILED',supply_state='PAUSED',reason=? WHERE round_id=?",
                            reason,
                            r);
                    var updated = sessions.advanceRevision(s);
                    queue.complete(updated, (String) row.get("run_id"), "FAILED");
                    return saveState(updated, r);
                });
    }

    private void saveRunBinding(Scope s, String r, String run, JsonNode binding) {
        jdbc.update(
                "INSERT INTO discovery.publication_run_binding VALUES(?,?,?,?::jsonb) ON CONFLICT(run_id) DO NOTHING",
                s.sessionId(),
                r,
                run,
                binding.toString());
        var stored =
                jdbc.queryForObject(
                        "SELECT binding::text FROM discovery.publication_run_binding WHERE run_id=? AND session_id=?",
                        String.class,
                        run,
                        s.sessionId());
        if (!same(json(stored), binding)) fail("IDEMPOTENCY_CONFLICT");
    }

    private JsonNode runBinding(Scope s, String r, String run, JsonNode fallback) {
        var rows =
                jdbc.queryForList(
                        "SELECT binding::text FROM discovery.publication_run_binding WHERE run_id=? AND session_id=? AND round_id=?",
                        String.class,
                        run,
                        s.sessionId(),
                        r);
        return rows.isEmpty() ? fallback : json(rows.getFirst());
    }

    @Override
    public String displayState(Scope s, String r) {
        return tx.write(
                () -> {
                    sessions.lockActive(s);
                    return display(s, r).toString();
                });
    }

    @Override
    public String candidateRound(Scope s, String candidate) {
        return tx.write(
                () -> {
                    sessions.lockActive(s);
                    var rows =
                            jdbc.queryForList(
                                    "SELECT round_id FROM discovery.recommendation_card WHERE session_id=? AND candidate_id=?",
                                    String.class,
                                    s.sessionId(),
                                    candidate);
                    if (rows.size() != 1) fail("RESOURCE_UNAVAILABLE");
                    return rows.getFirst();
                });
    }

    private String liveLongState(Scope scope, String run, String state) {
        if (state.equals("NEEDS_MORE_RESEARCH") && !discovery.physicalWork(scope, run))
            return "FAILED";
        if (!state.equals("GENERATING")) return state;
        var status = queue.status(scope, run).status();
        return !Set.of("WAITING", "DISPATCHING").contains(status)
                        && !discovery.physicalWork(scope, run)
                ? "INTERRUPTED"
                : state;
    }

    private String displayMode(Scope scope) {
        var modes =
                jdbc.queryForList(
                        "SELECT k.selection_basis FROM discovery.card_slot s JOIN discovery.recommendation_card k ON k.round_id=s.round_id AND k.candidate_id=s.candidate_id WHERE s.session_id=? AND s.state='READY'",
                        String.class,
                        scope.sessionId());
        if (modes.isEmpty()) return null;
        if (modes.contains("BASIC")) return "BASIC";
        return modes.stream().allMatch("CURATED"::equals) ? "CURATED" : "PERSONALIZED";
    }

    private void publishOrder(
            Context c,
            JsonNode payload,
            Map<String, Object> info,
            Map<String, JsonNode> candidates,
            com.fasterxml.jackson.databind.node.ArrayNode published) {
        var s = c.execution().evidence().scope();
        String r = c.execution().evidence().round();
        if (payload.path("basis_display_seq").asLong()
                != ((Number) round(s, r).get("display_seq")).longValue()) fail("BINDING_MISMATCH");
        boolean foreground = !payload.path("run_id").isNull();
        if (foreground && !payload.path("run_id").asText().equals(info.get("run_id")))
            fail("BINDING_MISMATCH");
        if (payload.path("analysis_mode").asText().equals("PERSONALIZED")
                && (!payload.path("selection").path("status").asText().equals("COMPLETED")
                        || !budget.completed(
                                c.execution().budget(),
                                payload.path("selection").path("invocation_id").asText(),
                                Set.of("SELECT", "STRUCTURE_REPAIR"))))
            fail("SELECTION_UNVERIFIED");
        var chosen = new ArrayList<String>();
        var seen = new HashSet<String>();
        int rank = 0;
        for (var item : payload.path("order")) {
            String id = item.path("candidate_id").asText();
            if (!seen.add(id) || !ready(s, r).contains(id)) fail("BINDING_MISMATCH");
            if (item.path("verdict").asText().equals("reject")) {
                jdbc.update(
                        "UPDATE discovery.round_candidate SET disposition='INELIGIBLE' WHERE round_id=? AND candidate_id=?",
                        r,
                        id);
                continue;
            }
            var card =
                    (ObjectNode)
                            json(
                                    jdbc.queryForObject(
                                            "SELECT content::text FROM discovery.recommendation_card WHERE round_id=? AND candidate_id=?",
                                            String.class,
                                            r,
                                            id));
            String basis =
                    payload.path("analysis_mode").asText().equals("PERSONALIZED")
                            ? "SELECTED"
                            : "SCREEN_ONLY";
            card.put("selection_basis", basis);
            if (cardFailure(c, payload, card, candidates.get(id)) != null) continue;
            jdbc.update(
                    "UPDATE discovery.round_candidate SET display_rank=? WHERE round_id=? AND candidate_id=?",
                    rank++,
                    r,
                    id);
            jdbc.update(
                    "UPDATE discovery.recommendation_card SET content=?::jsonb,selection_basis=? WHERE round_id=? AND candidate_id=?",
                    card.toString(),
                    basis.equals("SELECTED") ? "PERSONALIZED" : "BASIC",
                    r,
                    id);
            chosen.add(id);
        }
        if (foreground && !chosen.isEmpty()) {
            var slots =
                    jdbc.queryForList(
                            "SELECT slot_id,revision FROM discovery.card_slot WHERE session_id=? ORDER BY slot_id",
                            s.sessionId());
            int n = 0;
            for (var slot : slots) {
                String id = n < chosen.size() ? chosen.get(n++) : null;
                store.putSlot(
                        s,
                        (String) slot.get("slot_id"),
                        id == null ? null : r,
                        id,
                        id == null ? "EMPTY" : "READY",
                        ((Number) slot.get("revision")).longValue());
                if (id != null) {
                    jdbc.update(
                            "UPDATE discovery.round_candidate SET shown=true WHERE round_id=? AND candidate_id=?",
                            r,
                            id);
                    published.add(id);
                }
            }
            jdbc.update(
                    "UPDATE discovery.publication_round SET status=?,analysis_mode=?,supply_state='SUFFICIENT',reason=NULL WHERE round_id=?",
                    published.size() < c.target() ? "PARTIAL" : "SUCCEEDED",
                    displayMode(s),
                    r);
        } else if (foreground)
            jdbc.update(
                    "UPDATE discovery.publication_round SET status='PARTIAL',supply_state='PAUSED',reason='recommendation.personalization_incomplete' WHERE round_id=?",
                    r);
    }

    @Override
    public void scheduleLongs(Scope scope, String round, Set<String> candidates, Supply supply) {
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    sessions.lockActive(scope);
                    var eligible = new TreeSet<String>();
                    for (String id : candidates)
                        if (jdbc.queryForObject(
                                        "SELECT count(*) FROM discovery.long_review l JOIN discovery.card_slot s ON s.round_id=l.round_id AND s.candidate_id=l.candidate_id WHERE l.session_id=? AND l.round_id=? AND l.candidate_id=? AND l.state='NOT_STARTED' AND s.state='READY'",
                                        Long.class,
                                        scope.sessionId(),
                                        round,
                                        id)
                                > 0) eligible.add(id);
                    if (eligible.isEmpty()) return null;
                    String run = supply.enqueue(scope, round, eligible);
                    for (String id : eligible) {
                        if (run == null)
                            jdbc.update(
                                    "UPDATE discovery.long_review SET state='INTERRUPTED',reason='LONG_REVIEW_BUSY',revision=revision+1 WHERE round_id=? AND candidate_id=?",
                                    round,
                                    id);
                        else
                            jdbc.update(
                                    "UPDATE discovery.long_review SET generation_run=?,state='GENERATING',reason=NULL,revision=revision+1 WHERE round_id=? AND candidate_id=?",
                                    run,
                                    round,
                                    id);
                    }
                    saveState(sessions.advanceRevision(scope), round);
                    return null;
                });
    }
}
