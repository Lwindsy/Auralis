package io.auralis.evidence.adapters;

import static io.auralis.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.catalog.api.*;
import io.auralis.contracts.MessageDefinitions;
import io.auralis.discovery.api.ExecutionReadAccess;
import io.auralis.evidence.api.*;
import io.auralis.persistence.*;
import io.auralis.session.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Database-only coordinator use cases. All contexts and approved candidate bindings are
 * server-owned.
 */
@Service
public class PostgresEvidenceAuthority implements EvidenceAuthority, SessionDerivation {
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;
    private final EvidenceStore store;
    private final ExecutionReadAccess access;
    private final CatalogQuery catalog;
    private final CatalogVersion versions;
    private final ShortTransactions tx;
    private final MessageDefinitions schema;
    private final Clock clock;

    public PostgresEvidenceAuthority(
            JdbcTemplate jdbc,
            SessionStore sessions,
            EvidenceStore store,
            ExecutionReadAccess access,
            CatalogQuery catalog,
            CatalogVersion versions,
            ShortTransactions tx,
            MessageDefinitions schema,
            Clock clock) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.store = store;
        this.access = access;
        this.catalog = catalog;
        this.versions = versions;
        this.tx = tx;
        this.schema = schema;
        this.clock = clock;
    }

    private static void fail(String code) {
        throw new PersistenceRejected(code);
    }

    private JsonNode parse(String text, String definition) {
        try {
            var node = JSON.readTree(text);
            if (!schema.validDefinition(definition, node)) fail("INVALID_PAYLOAD");
            return node;
        } catch (java.io.IOException e) {
            throw new PersistenceRejected("INVALID_PAYLOAD");
        }
    }

    private static String url(String text) {
        if (!io.auralis.contracts.HttpsAddress.valid(text)) fail("READ_URL_INVALID");
        return text;
    }

    private <T> T run(Context c, Supplier<T> action) {
        if (c.readLimit() < 0 || c.catalogDataset() < 1) fail("INVALID_CONTEXT");
        return tx.write(
                () -> {
                    sessions.lockActive(c.scope());
                    access.guard(
                            c.scope(),
                            c.round(),
                            c.execution(),
                            c.coordinatorBoot(),
                            c.pythonBoot());
                    return action.get();
                });
    }

    private JsonNode raw(SessionStore.Scope scope) {
        return parse(
                sessions.current(scope.subjectId())
                        .orElseThrow(() -> new PersistenceRejected("STALE_SESSION_OR_BARRIER"))
                        .rawInput(),
                "RawInput");
    }

    private static Set<String> seedIds(JsonNode raw) {
        var ids = new LinkedHashSet<String>();
        for (var row : raw.path("liked_albums"))
            if (!row.path("release_group_id").isNull())
                ids.add(row.path("release_group_id").asText());
        return ids;
    }

    private UUID album(Context c, String id) {
        String value = c.candidates().get(id);
        if (value == null && seedIds(raw(c.scope())).contains(id)) value = id;
        if (value == null) {
            fail("READ_IDENTITY_UNKNOWN");
        }
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            throw new PersistenceRejected("READ_IDENTITY_UNKNOWN");
        }
    }

    @Override
    public String authorize(Context c, String body) {
        var request = parse(body, "ReadAuthorization");
        String read = request.path("read_id").asText(),
                candidate = request.path("candidate_id").asText(),
                purpose = request.path("purpose").asText(),
                address = url(request.path("url").asText());
        return run(
                c,
                () -> {
                    var id = album(c, candidate);
                    var identities = catalog.albums(c.catalogDataset(), List.of(id));
                    if (!identities.containsKey(id) || !identities.get(id).id().equals(id))
                        fail("READ_IDENTITY_UNKNOWN");
                    String hash = digest(JSON.getNodeFactory().textNode(address));
                    var tickets =
                            jdbc.queryForList(
                                    "SELECT execution_id,candidate_id,purpose FROM discovery.read_ticket WHERE read_id=?",
                                    read);
                    if (tickets.isEmpty()
                            && Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM discovery.seed_read_binding WHERE read_id=?)",
                                            Boolean.class,
                                            read))) fail("IDEMPOTENCY_CONFLICT");
                    if (!tickets.isEmpty()) {
                        var t = tickets.getFirst();
                        if (!c.execution().equals(t.get("execution_id"))
                                || !candidate.equals(t.get("candidate_id"))
                                || !purpose.equals(t.get("purpose"))) fail("IDEMPOTENCY_CONFLICT");
                    }
                    boolean exists =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM discovery.read_claim WHERE execution_id=? AND read_id=? AND url_digest=?)",
                                            Boolean.class,
                                            c.execution(),
                                            read,
                                            hash));
                    String decision, reason = null;
                    if (exists) decision = "ALREADY_CLAIMED";
                    else if (jdbc.queryForObject(
                                    "SELECT count(*) FROM discovery.read_claim WHERE round_id=?",
                                    Long.class,
                                    c.round())
                            >= c.readLimit()) {
                        decision = "DENIED";
                        reason = "CALL_LIMIT";
                    } else {
                        jdbc.update(
                                "INSERT INTO discovery.read_ticket VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(read_id) DO NOTHING",
                                read,
                                c.scope().sessionId(),
                                c.round(),
                                c.execution(),
                                candidate,
                                purpose,
                                address,
                                digest(identity(identities.get(id))),
                                Long.toString(c.catalogDataset()),
                                Timestamp.from(clock.instant()));
                        var owner =
                                jdbc.queryForMap(
                                        "SELECT execution_id,candidate_id,purpose FROM discovery.read_ticket WHERE read_id=?",
                                        read);
                        if (!c.execution().equals(owner.get("execution_id"))
                                || !candidate.equals(owner.get("candidate_id"))
                                || !purpose.equals(owner.get("purpose")))
                            fail("IDEMPOTENCY_CONFLICT");
                        store.claim(
                                c.scope(), c.round(), c.execution(), read, hash, clock.instant());
                        decision = "GRANTED";
                    }
                    var out =
                            JSON.createObjectNode().put("read_id", read).put("decision", decision);
                    if (reason == null) out.putNull("reason");
                    else out.put("reason", reason);
                    return out.toString();
                });
    }

    @Override
    public String receipts(Context c, String body) {
        var request = parse(body, "ReadReceipts");
        return run(
                c,
                () -> {
                    var out = JSON.createObjectNode();
                    var accepted = out.putArray("accepted_read_ids");
                    var rejected = out.putArray("rejected");
                    var seen = new HashSet<String>();
                    for (var receipt : request.path("items")) {
                        String read = receipt.path("read_id").asText(),
                                candidate = receipt.path("candidate_id").asText();
                        if (!seen.add(read)) fail("DUPLICATE_READ");
                        var ticket =
                                jdbc.queryForList(
                                        "SELECT * FROM discovery.read_ticket WHERE read_id=? AND execution_id=?",
                                        read,
                                        c.execution());
                        if (ticket.isEmpty()) {
                            rejected.addObject()
                                    .put("read_id", read)
                                    .put("reason", "READ_NOT_PERMITTED");
                            continue;
                        }
                        var t = ticket.getFirst();
                        if (!candidate.equals(t.get("candidate_id"))
                                || !receipt.path("purpose").asText().equals(t.get("purpose")))
                            fail("READ_BINDING_MISMATCH");
                        if (!receipt.path("requested_url").asText().equals(t.get("requested_url")))
                            fail("READ_BINDING_MISMATCH");
                        var at = Instant.parse(receipt.path("read_at").asText());
                        if (at.isAfter(clock.instant().plusSeconds(1))) fail("READ_TIME_INVALID");
                        boolean ok = receipt.path("status").asText().equals("READ_OK");
                        boolean prepared =
                                receipt.path("retrieval_mode").asText().equals("PREPARED_EXCERPT");
                        if (prepared && !c.allowPrepared()) fail("READ_NOT_PERMITTED");
                        if (ok
                                && !prepared
                                && at.isBefore(((Timestamp) t.get("issued_at")).toInstant()))
                            fail("READ_TIME_INVALID");
                        for (String key : List.of("requested_url", "final_url")) {
                            if (receipt.path(key).isNull()) {
                                if (ok) fail("READ_URL_INVALID");
                                continue;
                            }
                            String digest =
                                    digest(
                                            JSON.getNodeFactory()
                                                    .textNode(url(receipt.path(key).asText())));
                            if (!Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM discovery.read_claim WHERE execution_id=? AND read_id=? AND url_digest=?)",
                                            Boolean.class,
                                            c.execution(),
                                            read,
                                            digest))) fail("READ_NOT_PERMITTED");
                        }
                        if (ok
                                && (receipt.path("content_digest").isNull()
                                        || receipt.path("text_chars").asLong() < 1))
                            fail("READ_EVIDENCE_INVALID");
                        store.saveReceipt(
                                c.scope(),
                                new EvidenceStore.Receipt(
                                        read,
                                        c.round(),
                                        c.execution(),
                                        candidate,
                                        album(c, candidate),
                                        receipt.path("final_url").isNull()
                                                ? null
                                                : receipt.path("final_url").asText(),
                                        at,
                                        receipt.path("status").asText(),
                                        receipt.path("content_digest").isNull()
                                                ? null
                                                : receipt.path("content_digest").asText(),
                                        receipt.toString()));
                        if (ok) accepted.add(read);
                        else
                            rejected.addObject()
                                    .put("read_id", read)
                                    .put("reason", "READ_NOT_PERMITTED");
                    }
                    return out.toString();
                });
    }

    private ObjectNode identity(CatalogQuery.Album album) {
        var out =
                JSON.createObjectNode()
                        .put("title", album.title())
                        .put("artist_credit", album.artistCredit());
        if (album.year() == null) out.putNull("first_release_year");
        else out.put("first_release_year", album.year());
        if (album.primaryType() == null) out.putNull("release_type");
        else out.put("release_type", album.primaryType());
        var genres = out.putArray("primary_genres");
        album.genres()
                .forEach(
                        g -> genres.addObject().put("genre_id", g.genreId()).put("name", g.name()));
        return out;
    }

    private Map<String, ObjectNode> identities(SessionStore.Scope scope, long dataset) {
        var ids = seedIds(raw(scope));
        var uuids = new ArrayList<UUID>();
        for (String id : ids)
            try {
                uuids.add(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
            }
        var found = catalog.albums(dataset, uuids);
        var out = new LinkedHashMap<String, ObjectNode>();
        for (String id : ids)
            try {
                var a = found.get(UUID.fromString(id));
                if (a != null
                        && a.id().toString().equals(id)
                        && a.artistCredit() != null
                        && !a.artistCredit().isBlank()) out.put(id, identity(a));
            } catch (IllegalArgumentException e) {
            }
        return out;
    }

    private Map<String, JsonNode> bindings(String session) {
        var result = new HashMap<String, JsonNode>();
        jdbc.query(
                "SELECT read_id,receipt::text FROM discovery.seed_read_binding WHERE session_id=?",
                rs -> {
                    result.put(rs.getString(1), parse(rs.getString(2), "ReadReceipt"));
                },
                session);
        return result;
    }

    private JsonNode checkedRecord(
            String body, String id, JsonNode identity, Map<String, JsonNode> bindings) {
        var record = parse(body, "SeedEvidenceRecord");
        if (!record.path("seed_id").asText().equals(id)
                || !record.path("identity").equals(identity)
                || (record.path("status").asText().equals("READ_AVAILABLE")
                        != !record.path("sources").isEmpty())) fail("SEED_EVIDENCE_INVALID");
        for (var source : record.path("sources")) {
            var receipt = bindings.get(source.path("read_id").asText());
            String text = source.path("text").asText();
            if (receipt == null
                    || !receipt.path("candidate_id").asText().equals(id)
                    || !receipt.path("status").asText().equals("READ_OK")
                    || !receipt.path("final_url").equals(source.path("url"))
                    || !receipt.path("read_at").equals(source.path("read_at"))
                    || text.isBlank()
                    || text.getBytes(StandardCharsets.UTF_8).length > 3500
                    || !digest(source.path("text")).equals(source.path("text_digest").asText())
                    || !receipt.path("excerpt_digest").equals(source.path("text_digest")))
                fail("SEED_EVIDENCE_INVALID");
        }
        return record;
    }

    private String state(Context c) {
        var identities = identities(c.scope(), c.catalogDataset());
        var proofs = bindings(c.scope().sessionId());
        var saved = new HashMap<String, String>();
        jdbc.query(
                "SELECT seed_id,identity_revision,evidence::text FROM discovery.seed_evidence WHERE session_id=? AND data_revision=?",
                rs -> {
                    saved.put(rs.getString(1) + ":" + rs.getString(2), rs.getString(3));
                },
                c.scope().sessionId(),
                Long.toString(c.catalogDataset()));
        var out = JSON.createObjectNode();
        var items = out.putArray("items");
        var missing = out.putArray("missing_seed_ids");
        for (String id : seedIds(raw(c.scope()))) {
            var identity = identities.get(id);
            if (identity == null) {
                missing.add(id);
                continue;
            }
            var item = items.addObject().put("seed_id", id);
            item.set("identity", identity);
            String body = saved.get(id + ":" + digest(identity));
            if (body == null) item.putNull("evidence");
            else item.set("evidence", checkedRecord(body, id, identity, proofs));
        }
        if (!schema.validDefinition("SeedEvidenceState", out)) fail("SEED_EVIDENCE_INVALID");
        return out.toString();
    }

    @Override
    public String seeds(Context c) {
        return run(c, () -> state(c));
    }

    @Override
    public String saveSeeds(Context c, String body) {
        var updates = parse(body, "SeedEvidenceUpdate");
        return run(
                c,
                () -> {
                    var identities = identities(c.scope(), c.catalogDataset());
                    var seen = new HashSet<String>();
                    for (var record : updates.path("items")) {
                        String id = record.path("seed_id").asText();
                        if (!seen.add(id)) fail("DUPLICATE_SEED");
                        if (!record.path("identity").equals(identities.get(id)))
                            fail("BINDING_MISMATCH");
                        boolean available = record.path("status").asText().equals("READ_AVAILABLE");
                        if (available != !record.path("sources").isEmpty())
                            fail("SEED_EVIDENCE_INVALID");
                        String read = null, content = null;
                        Instant at = null;
                        for (var source : record.path("sources")) {
                            read = source.path("read_id").asText();
                            var stored =
                                    jdbc.queryForList(
                                            "SELECT r.receipt::text FROM discovery.read_receipt r JOIN discovery.read_ticket t ON t.read_id=r.id WHERE r.id=? AND r.session_id=? AND r.candidate_id=? AND r.status='READ_OK' AND t.identity_revision=? AND t.data_revision=?",
                                            String.class,
                                            read,
                                            c.scope().sessionId(),
                                            id,
                                            digest(record.path("identity")),
                                            Long.toString(c.catalogDataset()));
                            if (stored.size() != 1) fail("READ_NOT_PERMITTED");
                            var receipt = parse(stored.getFirst(), "ReadReceipt");
                            String text = source.path("text").asText();
                            content = source.path("text_digest").asText();
                            if (text.isBlank()
                                    || text.getBytes(StandardCharsets.UTF_8).length > 3500
                                    || !digest(JSON.getNodeFactory().textNode(text)).equals(content)
                                    || !receipt.path("excerpt_digest").asText().equals(content)
                                    || !source.path("url").equals(receipt.path("final_url"))
                                    || !source.path("read_at").equals(receipt.path("read_at")))
                                fail("SEED_EVIDENCE_INVALID");
                            at = Instant.parse(source.path("read_at").asText());
                        }
                        var expiry =
                                sessions.current(c.scope().subjectId()).orElseThrow().expiresAt();
                        var old = store.seed(c.scope(), id, digest(record.path("identity")));
                        if (old.isPresent()) {
                            if (!parse(old.get(), "SeedEvidenceRecord").equals(record))
                                fail("IDEMPOTENCY_CONFLICT");
                            continue;
                        }
                        store.saveSeed(
                                c.scope(),
                                new EvidenceStore.Seed(
                                        id,
                                        digest(record.path("identity")),
                                        Long.toString(c.catalogDataset()),
                                        read,
                                        null,
                                        content,
                                        at,
                                        expiry,
                                        record.path("status").asText(),
                                        record.toString()));
                    }
                    return state(c);
                });
    }

    @Override
    public boolean usableRead(Context c, String read, String candidate, boolean seed) {
        return run(
                c,
                () -> {
                    if (seed) {
                        if (!seedIds(raw(c.scope())).contains(candidate)) return false;
                        var state = parse(state(c), "SeedEvidenceState");
                        for (var item : state.path("items"))
                            if (item.path("seed_id").asText().equals(candidate))
                                for (var source : item.path("evidence").path("sources"))
                                    if (source.path("read_id").asText().equals(read)) return true;
                        return false;
                    }
                    return Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM discovery.read_receipt WHERE id=? AND session_id=? AND round_id=? AND execution_id=? AND candidate_id=? AND status='READ_OK')",
                                    Boolean.class,
                                    read,
                                    c.scope().sessionId(),
                                    c.round(),
                                    c.execution(),
                                    candidate));
                });
    }

    @Override
    public void derive(String source, SessionStore.Scope target, String input) {
        ShortTransactions.requireWrite();
        if (seedIds(parse(input, "RawInput")).isEmpty()) return;
        sessions.lockActive(target);
        var owners =
                jdbc.queryForList(
                        "SELECT id FROM discovery.session WHERE id=? AND subject_id=? AND status IN ('ACTIVE','SUPERSEDED') AND expires_at>clock_timestamp()",
                        String.class,
                        source,
                        target.subjectId());
        if (owners.size() != 1) return;
        long dataset = versions.current();
        var identities = identities(target, dataset);
        var sourceProofs = bindings(source);
        var expiry = sessions.current(target.subjectId()).orElseThrow().expiresAt();
        for (var entry : identities.entrySet()) {
            var rows =
                    jdbc.queryForList(
                            "SELECT evidence::text FROM discovery.seed_evidence WHERE session_id=? AND seed_id=? AND identity_revision=? AND data_revision=? FOR UPDATE",
                            String.class,
                            source,
                            entry.getKey(),
                            digest(entry.getValue()),
                            Long.toString(dataset));
            if (rows.isEmpty()) continue;
            var copied =
                    (ObjectNode)
                            checkedRecord(
                                            rows.getFirst(),
                                            entry.getKey(),
                                            entry.getValue(),
                                            sourceProofs)
                                    .deepCopy();
            String newRead = null, sourceRead = null, content = null;
            Instant at = null;
            for (var value : copied.path("sources")) {
                var item = (ObjectNode) value;
                sourceRead = item.path("read_id").asText();
                newRead = UUID.randomUUID().toString();
                var receipts =
                        jdbc.queryForList(
                                "SELECT receipt::text FROM discovery.seed_read_binding WHERE session_id=? AND read_id=? AND seed_id=?",
                                String.class,
                                source,
                                sourceRead,
                                entry.getKey());
                if (receipts.size() != 1) fail("SEED_PROVENANCE_MISSING");
                var receipt = (ObjectNode) parse(receipts.getFirst(), "ReadReceipt").deepCopy();
                content = item.path("text_digest").asText();
                if (!digest(item.path("text")).equals(content)
                        || !receipt.path("excerpt_digest").asText().equals(content)
                        || !receipt.path("final_url").equals(item.path("url"))
                        || !receipt.path("read_at").equals(item.path("read_at")))
                    fail("SEED_EVIDENCE_INVALID");
                receipt.put("read_id", newRead);
                item.put("read_id", newRead);
                at = Instant.parse(item.path("read_at").asText());
                jdbc.update(
                        "INSERT INTO discovery.seed_read_binding(session_id,read_id,seed_id,source_read_id,source_session_id,receipt) VALUES(?,?,?,?,?,?::jsonb)",
                        target.sessionId(),
                        newRead,
                        entry.getKey(),
                        sourceRead,
                        source,
                        receipt.toString());
            }
            store.saveSeed(
                    target,
                    new EvidenceStore.Seed(
                            entry.getKey(),
                            digest(entry.getValue()),
                            Long.toString(dataset),
                            newRead,
                            sourceRead,
                            content,
                            at,
                            expiry,
                            copied.path("status").asText(),
                            copied.toString()));
        }
    }
}
