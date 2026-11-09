package io.auralis.budget.adapters;

import static io.auralis.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.budget.api.BudgetAuthority;
import io.auralis.contracts.MessageDefinitions;
import io.auralis.discovery.api.ExecutionReadAccess;
import io.auralis.evidence.api.EvidenceAuthority;
import io.auralis.persistence.*;
import io.auralis.session.api.SessionStore;
import java.math.BigInteger;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Short, database-only authorization and settlement transactions. */
@Service
public class PostgresBudgetAuthority implements BudgetAuthority {
    private final JdbcTemplate jdbc;
    private final ShortTransactions tx;
    private final SessionStore sessions;
    private final ExecutionReadAccess executions;
    private final EvidenceAuthority evidence;
    private final MessageDefinitions schema;

    public PostgresBudgetAuthority(
            JdbcTemplate jdbc,
            ShortTransactions tx,
            SessionStore sessions,
            ExecutionReadAccess executions,
            EvidenceAuthority evidence,
            MessageDefinitions schema) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sessions = sessions;
        this.executions = executions;
        this.evidence = evidence;
        this.schema = schema;
    }

    private static void fail(String code) {
        throw new PersistenceRejected(code);
    }

    private static String hash(String text) {
        return digest(JSON.valueToTree(text));
    }

    private JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new PersistenceRejected("INVALID_PAYLOAD");
        }
    }

    private JsonNode parse(String text, String definition) {
        var n = json(text);
        if (!schema.validDefinition(definition, n)) fail("INVALID_PAYLOAD");
        return n;
    }

    private String result(JsonNode n, String definition) {
        if (!schema.validDefinition(definition, n)) throw new IllegalStateException(definition);
        return n.toString();
    }

    private void guard(Context c) {
        var e = c.evidence();
        sessions.lockActive(e.scope());
        executions.guard(e.scope(), e.round(), e.execution(), e.coordinatorBoot(), e.pythonBoot());
    }

    private String lane(Context c, String lane) {
        return hash(c.roundScope() + ":" + lane);
    }

    private List<String> ids(Context c) {
        return List.of(
                c.sessionScope(), c.roundScope(), lane(c, "FOREGROUND"), lane(c, "BACKGROUND"));
    }

    private ObjectNode policy(Context c) {
        var p =
                JSON.createObjectNode()
                        .put("round", hash(c.evidence().round()))
                        .put("session", hash(c.evidence().scope().sessionId()))
                        .put("session_scope", c.sessionScope())
                        .put("session_cap", c.sessionCap())
                        .put("round_cap", c.roundCap())
                        .put("foreground", c.foregroundCap())
                        .put("background", c.backgroundCap())
                        .put("model_calls", c.modelCalls())
                        .put("search_calls", c.searchCalls());
        p.set("prices", JSON.valueToTree(c.prices()));
        return p;
    }

    private void validate(Context c) {
        if (c.sessionCap() < 0
                || c.roundCap() < 0
                || c.foregroundCap() < 0
                || c.backgroundCap() < 0
                || c.sessionCap() > Integer.MAX_VALUE
                || c.roundCap() > Integer.MAX_VALUE
                || c.foregroundCap() > Integer.MAX_VALUE
                || c.backgroundCap() > Integer.MAX_VALUE
                || c.roundCap() > c.sessionCap()
                || c.foregroundCap() > c.roundCap() - c.backgroundCap()
                || c.modelCalls() < 0
                || c.modelCalls() > 10000
                || c.searchCalls() < 0
                || c.searchCalls() > 10000
                || c.prices().isEmpty()
                || new HashSet<>(ids(c)).size() != 4) fail("INVALID_BUDGET_CONFIGURATION");
        for (var p : c.prices().values())
            if (p.revision() == null
                    || p.revision().isBlank()
                    || p.input() < 0
                    || p.cachedInput() < 0
                    || p.cachedInput() > p.input()
                    || p.output() < 0
                    || p.search() < 0
                    || p.maxInput() < 1
                    || p.maxOutput() < 1) fail("INVALID_PRICE_CONFIGURATION");
    }

    @Override
    public void register(Context c) {
        validate(c);
        tx.write(
                () -> {
                    guard(c);
                    if (count(
                                    "SELECT count(*) FROM discovery.budget_scope WHERE session_id=? AND parent_scope_id IS NULL AND id<>?",
                                    c.evidence().scope().sessionId(),
                                    c.sessionScope())
                            > 0) fail("BUDGET_BINDING_MISMATCH");
                    create(
                            c,
                            c.sessionScope(),
                            null,
                            c.sessionCap(),
                            JSON.createObjectNode()
                                    .put("session", hash(c.evidence().scope().sessionId())));
                    create(c, c.roundScope(), c.sessionScope(), c.roundCap(), policy(c));
                    create(
                            c,
                            lane(c, "FOREGROUND"),
                            c.roundScope(),
                            c.foregroundCap(),
                            JSON.createObjectNode());
                    create(
                            c,
                            lane(c, "BACKGROUND"),
                            c.roundScope(),
                            c.backgroundCap(),
                            JSON.createObjectNode());
                    return null;
                });
    }

    private void create(Context c, String id, String parent, long cap, JsonNode policy) {
        jdbc.update(
                "INSERT INTO discovery.budget_scope(id,session_id,parent_scope_id,cap_microusd,price_revision,policy) VALUES(?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO NOTHING",
                id,
                c.evidence().scope().sessionId(),
                parent,
                cap,
                "explicit-v1",
                policy.toString());
        var row =
                jdbc.queryForMap(
                        "SELECT session_id,parent_scope_id,cap_microusd,policy::text FROM discovery.budget_scope WHERE id=?",
                        id);
        if (!Objects.equals(row.get("session_id"), c.evidence().scope().sessionId())
                || !Objects.equals(row.get("parent_scope_id"), parent)
                || ((Number) row.get("cap_microusd")).longValue() != cap
                || !digest(json((String) row.get("policy"))).equals(digest(policy)))
            fail("BUDGET_BINDING_MISMATCH");
    }

    private void lock(List<String> ids) {
        for (String id : ids.stream().distinct().sorted().toList()) {
            LockOrder.before(LockOrder.Level.BUDGET, id);
            if (jdbc.queryForList("SELECT id FROM discovery.budget_scope WHERE id=? FOR UPDATE", id)
                            .size()
                    != 1) fail("BUDGET_NOT_REGISTERED");
        }
    }

    private void binding(Context c) {
        validate(c);
        var rows =
                jdbc.queryForList(
                        "SELECT policy::text FROM discovery.budget_scope WHERE id=? AND session_id=?",
                        String.class,
                        c.roundScope(),
                        c.evidence().scope().sessionId());
        if (rows.size() != 1 || !digest(json(rows.getFirst())).equals(digest(policy(c))))
            fail("BUDGET_BINDING_MISMATCH");
    }

    private long remaining(String id) {
        return jdbc.queryForObject(
                "SELECT greatest(0,cap_microusd-settled_microusd-reserved_microusd) FROM discovery.budget_scope WHERE id=?",
                Long.class,
                id);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long amount(Price p, long input, long cached, long output, boolean search) {
        var total =
                BigInteger.valueOf(input - cached)
                        .multiply(BigInteger.valueOf(p.input()))
                        .add(
                                BigInteger.valueOf(cached)
                                        .multiply(BigInteger.valueOf(p.cachedInput())))
                        .add(BigInteger.valueOf(output).multiply(BigInteger.valueOf(p.output())));
        return total.add(BigInteger.valueOf(999999))
                .divide(BigInteger.valueOf(1000000))
                .add(BigInteger.valueOf(search ? p.search() : 0))
                .longValueExact();
    }

    private List<String> deniedReads(Context c, JsonNode plan) {
        var seeds = new HashSet<String>();
        var raw = json(sessions.current(c.evidence().scope().subjectId()).orElseThrow().rawInput());
        for (var seed : raw.path("liked_albums"))
            if (seed.path("release_group_id").isTextual())
                seeds.add(seed.path("release_group_id").asText());
        String candidate =
                plan.path("candidate_id").isNull() ? null : plan.path("candidate_id").asText();
        if (candidate != null
                && !c.evidence().candidates().containsKey(candidate)
                && !seeds.contains(candidate)) fail("BINDING_MISMATCH");
        var denied = new ArrayList<String>();
        for (var read : plan.path("read_ids")) {
            boolean valid = false;
            for (String id : seeds)
                valid |= evidence.usableRead(c.evidence(), read.asText(), id, true);
            if (candidate != null && !seeds.contains(candidate))
                valid |= evidence.usableRead(c.evidence(), read.asText(), candidate, false);
            else if (candidate == null)
                for (String id : c.evidence().candidates().keySet())
                    valid |= evidence.usableRead(c.evidence(), read.asText(), id, false);
            if (!valid) denied.add(read.asText());
        }
        return denied;
    }

    @Override
    public String grants(Context c, String body) {
        var request = parse(body, "InvocationGrants");
        return tx.write(
                () -> {
                    guard(c);
                    binding(c);
                    var reads = new HashMap<String, List<String>>();
                    for (var plan : request.path("items"))
                        reads.put(plan.path("invocation_id").asText(), deniedReads(c, plan));
                    lock(ids(c));
                    var plans = new ArrayList<JsonNode>();
                    request.path("items").forEach(plans::add);
                    plans.sort(Comparator.comparing(p -> p.path("invocation_id").asText()));
                    var decisions = new HashMap<String, JsonNode>();
                    for (var plan : plans) {
                        String id = plan.path("invocation_id").asText();
                        if (decisions.containsKey(id)) fail("DUPLICATE_INVOCATION");
                        decisions.put(id, grant(c, plan, reads.get(id)));
                    }
                    var response = JSON.createObjectNode();
                    var items = response.putArray("items");
                    for (var plan : request.path("items"))
                        items.add(decisions.get(plan.path("invocation_id").asText()));
                    return result(response, "GrantResults");
                });
    }

    private JsonNode grant(Context c, JsonNode p, List<String> denied) {
        String id = p.path("invocation_id").asText(),
                lane = p.path("lane").asText(),
                model = p.path("model_binding_ref").asText();
        Price price = c.prices().get(model);
        if (price == null
                || !id.startsWith(c.evidence().execution() + ":")
                || (lane.equals("FOREGROUND") && !p.path("run_id").asText().equals(c.run()))
                || p.path("max_input_tokens").asLong() > price.maxInput()
                || p.path("max_output_tokens").asLong() > price.maxOutput())
            fail("BINDING_MISMATCH");
        if (p.path("purpose").asText().equals("SEARCH")
                != p.path("cost_class").asText().equals("TOOL_SERVICE")) fail("BINDING_MISMATCH");
        LockOrder.before(LockOrder.Level.INVOCATION, id);
        var old =
                jdbc.queryForList(
                        "SELECT request_digest,reserved_microusd,execution_digest,grant_result::text FROM discovery.invocation WHERE id=? FOR UPDATE",
                        id);
        String reason = null, decision = "GRANTED";
        long amount =
                amount(
                        price,
                        p.path("max_input_tokens").asLong(),
                        0,
                        p.path("max_output_tokens").asLong(),
                        p.path("cost_class").asText().equals("TOOL_SERVICE"));
        if (amount > Integer.MAX_VALUE) fail("POLICY_OUT_OF_RANGE");
        if (!old.isEmpty()) {
            var row = old.getFirst();
            if (!digest(p).equals(row.get("request_digest"))
                    || !hash(c.evidence().execution()).equals(row.get("execution_digest")))
                fail("IDEMPOTENCY_CONFLICT");
            if (row.get("grant_result") != null) return json((String) row.get("grant_result"));
            decision = "ALREADY_CLAIMED";
            amount = ((Number) row.get("reserved_microusd")).longValue();
        } else {
            String costClass = p.path("cost_class").asText();
            if (count(
                            "SELECT count(*) FROM discovery.invocation WHERE model_ref=? AND settled_microusd>reserved_microusd",
                            model)
                    > 0) reason = "EXECUTION_NOT_ACTIVE";
            else if (!denied.isEmpty()) reason = "READ_UNKNOWN";
            else if (remaining(c.sessionScope()) < amount) reason = "SESSION_BUDGET_EXHAUSTED";
            else if (remaining(c.roundScope()) < amount) reason = "ROUND_BUDGET_EXHAUSTED";
            else if (remaining(lane(c, lane)) < amount) reason = "LANE_EXHAUSTED";
            else if (count(
                            "SELECT count(*) FROM discovery.invocation WHERE scope_id=? AND cost_class=? AND send_consumed",
                            c.roundScope(),
                            costClass)
                    >= (costClass.equals("MODEL") ? c.modelCalls() : c.searchCalls()))
                reason = "CALL_LIMIT";
            else if (p.path("purpose").asText().equals("STRUCTURE_REPAIR")
                    && count(
                                    "SELECT count(*) FROM discovery.invocation WHERE scope_id=? AND lane=? AND send_consumed AND purpose='STRUCTURE_REPAIR' AND (?='BACKGROUND' OR run_digest=?)",
                                    c.roundScope(),
                                    lane,
                                    lane,
                                    hash(c.run()))
                            >= 1) reason = "REPAIR_QUOTA_EXHAUSTED";
            if (reason != null) {
                decision = "DENIED";
                amount = 0;
            } else {
                for (String scope : List.of(c.sessionScope(), c.roundScope(), lane(c, lane)))
                    jdbc.update(
                            "UPDATE discovery.budget_scope SET reserved_microusd=reserved_microusd+?,revision=revision+1 WHERE id=?",
                            amount,
                            scope);
                jdbc.update(
                        "INSERT INTO discovery.invocation(id,session_id,round_id,execution_id,scope_id,lane,purpose,request_digest,reserved_microusd,outcome,send_consumed,price_binding,execution_digest,run_digest,cost_class,model_ref) VALUES(?,?,?,?,?,?,?,?,?,'RESERVED',true,?::jsonb,?,?,?,?)",
                        id,
                        c.evidence().scope().sessionId(),
                        c.evidence().round(),
                        c.evidence().execution(),
                        c.roundScope(),
                        lane,
                        p.path("purpose").asText(),
                        digest(p),
                        amount,
                        JSON.valueToTree(price).toString(),
                        hash(c.evidence().execution()),
                        hash(c.run()),
                        costClass,
                        model);
            }
        }
        var r =
                JSON.createObjectNode()
                        .put("invocation_id", id)
                        .put("decision", decision)
                        .put("reserved_microusd", amount)
                        .put("deny_reason", reason)
                        .put("lane_remaining_microusd", remaining(lane(c, lane)))
                        .put("session_remaining_microusd", remaining(c.sessionScope()));
        r.set("not_permitted_read_ids", JSON.valueToTree(denied));
        if (decision.equals("DENIED")) {
            jdbc.update(
                    "INSERT INTO discovery.invocation(id,session_id,round_id,execution_id,scope_id,lane,purpose,request_digest,reserved_microusd,outcome,send_consumed,price_binding,execution_digest,run_digest,cost_class,model_ref,settled_microusd,grant_result) VALUES(?,?,?,?,?,?,?,?,0,'DENIED',false,?::jsonb,?,?,?,?,0,?::jsonb)",
                    id,
                    c.evidence().scope().sessionId(),
                    c.evidence().round(),
                    c.evidence().execution(),
                    c.roundScope(),
                    lane,
                    p.path("purpose").asText(),
                    digest(p),
                    JSON.valueToTree(price).toString(),
                    hash(c.evidence().execution()),
                    hash(c.run()),
                    p.path("cost_class").asText(),
                    model,
                    r.toString());
        }
        return r;
    }

    private List<String> financialScopes(String round) {
        var rows =
                jdbc.queryForList(
                        "SELECT id FROM discovery.budget_scope WHERE id=? OR parent_scope_id=? OR id=(SELECT parent_scope_id FROM discovery.budget_scope WHERE id=?)",
                        String.class,
                        round,
                        round,
                        round);
        if (rows.size() != 4) fail("BUDGET_BINDING_MISMATCH");
        return rows;
    }

    private ObjectNode summaries(String round) {
        var out = JSON.createObjectNode();
        for (String lane : List.of("FOREGROUND", "BACKGROUND")) {
            var row =
                    jdbc.queryForMap(
                            "SELECT reserved_microusd,settled_microusd FROM discovery.budget_scope WHERE id=?",
                            hash(round + ":" + lane));
            out.putObject(lane)
                    .put("reserved_microusd", ((Number) row.get("reserved_microusd")).longValue())
                    .put("settled_microusd", ((Number) row.get("settled_microusd")).longValue())
                    .put(
                            "uncertain_invocations",
                            count(
                                    "SELECT count(*) FROM discovery.invocation WHERE scope_id=? AND lane=? AND settled_microusd IS NULL",
                                    round,
                                    lane));
        }
        return out;
    }

    private long sequence(String round) {
        return count("SELECT revision FROM discovery.budget_scope WHERE id=?", round);
    }

    @Override
    public String settle(String execution, String body) {
        var r = parse(body, "UsageReceipt");
        String id = r.path("invocation_id").asText(), receipt = r.path("receipt_id").asText();
        return tx.write(
                () -> {
                    var owners =
                            jdbc.queryForList(
                                    "SELECT scope_id FROM discovery.invocation WHERE id=? AND execution_digest=? AND send_consumed",
                                    String.class,
                                    id,
                                    hash(execution));
                    if (owners.size() != 1) fail("BINDING_MISMATCH");
                    String round = owners.getFirst();
                    lock(financialScopes(round));
                    LockOrder.before(LockOrder.Level.INVOCATION, id);
                    var invocation =
                            jdbc.queryForMap(
                                    "SELECT *,price_binding::text AS price FROM discovery.invocation WHERE id=? FOR UPDATE",
                                    id);
                    var prior =
                            jdbc.queryForList(
                                    "SELECT payload_digest,response::text FROM discovery.usage_receipt WHERE invocation_id=? AND receipt_id=?",
                                    id,
                                    receipt);
                    if (!prior.isEmpty()) {
                        if (!digest(r).equals(prior.getFirst().get("payload_digest")))
                            fail("IDEMPOTENCY_CONFLICT");
                        return (String) prior.getFirst().get("response");
                    }
                    String outcome = r.path("outcome").asText(),
                            previous = (String) invocation.get("outcome");
                    Long charge = (Long) invocation.get("settled_microusd");
                    Price price;
                    try {
                        price =
                                JSON.treeToValue(
                                        json((String) invocation.get("price")), Price.class);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                    boolean known =
                            outcome.equals("COMPLETED")
                                    && !r.path("input_tokens").isNull()
                                    && !r.path("output_tokens").isNull()
                                    && (!r.path("cached_input_tokens").isNull()
                                            || price.input() == price.cachedInput());
                    boolean unsent = outcome.equals("NOT_PROCESSED") && previous.equals("RESERVED");
                    if (outcome.equals("NOT_PROCESSED")
                            && (r.path("input_tokens").asLong() > 0
                                    || r.path("cached_input_tokens").asLong() > 0
                                    || r.path("output_tokens").asLong() > 0)) fail("INVALID_USAGE");
                    if (!r.path("cached_input_tokens").isNull()
                            && (r.path("input_tokens").isNull()
                                    || r.path("cached_input_tokens").asLong()
                                            > r.path("input_tokens").asLong()))
                        fail("INVALID_USAGE");
                    if (charge != null
                            && known
                            && (charge
                                            != amount(
                                                    price,
                                                    r.path("input_tokens").asLong(),
                                                    r.path("cached_input_tokens").asLong(),
                                                    r.path("output_tokens").asLong(),
                                                    invocation
                                                            .get("cost_class")
                                                            .equals("TOOL_SERVICE"))
                                    || !Objects.equals(
                                            invocation.get("input_tokens"),
                                            nullable(r, "input_tokens"))
                                    || !Objects.equals(
                                            invocation.get("cached_input_tokens"),
                                            nullable(r, "cached_input_tokens"))
                                    || !Objects.equals(
                                            invocation.get("output_tokens"),
                                            nullable(r, "output_tokens"))))
                        fail("IDEMPOTENCY_CONFLICT");
                    if (charge == null && (known || unsent)) {
                        charge =
                                unsent
                                        ? 0
                                        : amount(
                                                price,
                                                r.path("input_tokens").asLong(),
                                                r.path("cached_input_tokens").asLong(),
                                                r.path("output_tokens").asLong(),
                                                invocation
                                                        .get("cost_class")
                                                        .equals("TOOL_SERVICE"));
                        long reserved = ((Number) invocation.get("reserved_microusd")).longValue();
                        String parent =
                                jdbc.queryForObject(
                                        "SELECT parent_scope_id FROM discovery.budget_scope WHERE id=?",
                                        String.class,
                                        round);
                        for (String scope :
                                List.of(parent, round, hash(round + ":" + invocation.get("lane"))))
                            jdbc.update(
                                    "UPDATE discovery.budget_scope SET reserved_microusd=reserved_microusd-?,settled_microusd=settled_microusd+?,revision=revision+1 WHERE id=?",
                                    reserved,
                                    charge,
                                    scope);
                        jdbc.update(
                                "UPDATE discovery.invocation SET settled_microusd=?,outcome=?,input_tokens=?,cached_input_tokens=?,output_tokens=?,finish=? WHERE id=?",
                                charge,
                                outcome,
                                nullable(r, "input_tokens"),
                                nullable(r, "cached_input_tokens"),
                                nullable(r, "output_tokens"),
                                r.path("finish").asText(),
                                id);
                    } else if (charge == null) {
                        // An uncertain/processed call can never be downgraded to definitely unsent.
                        if (!outcome.equals("NOT_PROCESSED"))
                            jdbc.update(
                                    "UPDATE discovery.invocation SET outcome=?,input_tokens=?,cached_input_tokens=?,output_tokens=?,finish=? WHERE id=?",
                                    outcome,
                                    nullable(r, "input_tokens"),
                                    nullable(r, "cached_input_tokens"),
                                    nullable(r, "output_tokens"),
                                    r.path("finish").asText(),
                                    id);
                    }
                    jdbc.update(
                            "UPDATE discovery.budget_scope SET revision=revision+1 WHERE id=?",
                            round);
                    long seq = sequence(round);
                    var response =
                            JSON.createObjectNode()
                                    .put("accepted", true)
                                    .put("invocation_id", id)
                                    .put("status", charge == null ? "UNCERTAIN" : "SETTLED")
                                    .put("settlement_seq", seq);
                    response.set("lanes", summaries(round));
                    String responseText = result(response, "UsageSettlement");
                    jdbc.update(
                            "INSERT INTO discovery.usage_receipt(invocation_id,receipt_id,payload_digest,settlement_seq,cost_microusd,input_tokens,output_tokens,outcome,response) VALUES(?,?,?,?,?,?,?,?,?::jsonb)",
                            id,
                            receipt,
                            digest(r),
                            seq,
                            charge,
                            nullable(r, "input_tokens"),
                            nullable(r, "output_tokens"),
                            outcome,
                            responseText);
                    return responseText;
                });
    }

    private static Long nullable(JsonNode r, String key) {
        return r.path(key).isNull() ? null : r.path(key).asLong();
    }

    @Override
    public boolean completed(Context c, String invocation) {
        binding(c);
        return count(
                        "SELECT count(*) FROM discovery.invocation WHERE id=? AND scope_id=? AND send_consumed AND outcome='COMPLETED' AND finish='COMPLETE'",
                        invocation,
                        c.roundScope())
                == 1;
    }

    @Override
    public String state(Context c) {
        return tx.write(
                () -> {
                    sessions.lockActive(c.evidence().scope());
                    binding(c);
                    lock(ids(c));
                    var r =
                            JSON.createObjectNode()
                                    .put("settlement_seq", sequence(c.roundScope()))
                                    .put("session_remaining_microusd", remaining(c.sessionScope()))
                                    .put("round_remaining_microusd", remaining(c.roundScope()))
                                    .put(
                                            "structure_repairs",
                                            count(
                                                    "SELECT count(*) FROM discovery.invocation WHERE scope_id=? AND send_consumed AND purpose='STRUCTURE_REPAIR'",
                                                    c.roundScope()));
                    r.set("lanes", summaries(c.roundScope()));
                    r.putObject("lane_caps")
                            .put("FOREGROUND", c.foregroundCap())
                            .put("BACKGROUND", c.backgroundCap());
                    for (String field : List.of("input_tokens", "cached_input_tokens")) {
                        Long sum =
                                jdbc.queryForObject(
                                        "SELECT CASE WHEN count(*)=count("
                                                + field
                                                + ") THEN sum("
                                                + field
                                                + ") ELSE NULL END FROM discovery.invocation WHERE scope_id=? AND send_consumed",
                                        Long.class,
                                        c.roundScope());
                        if (sum == null) r.putNull(field);
                        else r.put(field, sum);
                    }
                    return result(r, "BudgetState");
                });
    }

    @Override
    public boolean completed(Context c, String invocation, Set<String> purposes) {
        if (!completed(c, invocation)) return false;
        String purpose =
                jdbc.queryForObject(
                        "SELECT purpose FROM discovery.invocation WHERE id=?",
                        String.class,
                        invocation);
        return purposes.contains(purpose);
    }
}
