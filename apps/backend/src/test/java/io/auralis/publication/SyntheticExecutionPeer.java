package io.auralis.publication;

import static io.auralis.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import io.auralis.bootstrap.WireProtocol;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic Python boundary for product/browser tests; never packaged as a product provider. */
final class SyntheticExecutionPeer implements AutoCloseable {
    static final String TOKEN = "be003-synthetic-service-token-1234567890";
    final String boot = UUID.randomUUID().toString();
    final HttpServer server;
    final ExecutorService httpWorkers = Executors.newFixedThreadPool(4),
            workers = Executors.newFixedThreadPool(3);
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    final WireProtocol wire = new WireProtocol(JSON);
    final Map<String, Job> jobs = new ConcurrentHashMap<>();
    final List<String> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger sends = new AtomicInteger(), modelCalls = new AtomicInteger();
    final AtomicBoolean basicNext = new AtomicBoolean();
    final AtomicBoolean failLongNext = new AtomicBoolean();
    volatile boolean hold, holdReports;
    volatile long longDelayMs = 600;
    volatile int recallLimit = Integer.MAX_VALUE;
    volatile String controlBase;
    final JsonNode settings;
    final List<String> albums;

    static final class Job {
        final ObjectNode start;
        final boolean basic, failLong;
        final Map<String, String> reads = new HashMap<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final AtomicInteger delivery = new AtomicInteger(), calls = new AtomicInteger();
        final Map<String, String> commands = new ConcurrentHashMap<>();
        volatile boolean cancelled, released;
        volatile String status = "ACCEPTED", phase = "FOREGROUND";
        volatile JsonNode display;
        JsonNode bundle;
        int longReady;

        Job(ObjectNode start, boolean basic, boolean failLong) {
            this.start = start;
            this.basic = basic;
            this.failLong = failLong;
        }

        String id() {
            return start.path("execution_id").asText();
        }
    }

    SyntheticExecutionPeer(JsonNode settings, List<String> albums) throws Exception {
        this.settings = settings.deepCopy();
        this.albums = List.copyOf(albums);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpWorkers);
        server.createContext("/internal/v1", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ObjectNode envelope(String type, JsonNode payload, String request) {
        var n =
                JSON.createObjectNode()
                        .put("schema_version", "intel.v1")
                        .put("contract_revision", wire.revision())
                        .put("request_id", request)
                        .put("message_type", type);
        n.putObject("trace_context").put("trace_id", "synthetic").put("span_id", "test");
        n.set("payload", payload);
        return n;
    }

    private ObjectNode view(Job j) {
        var v =
                JSON.createObjectNode()
                        .put("execution_id", j.id())
                        .put("round_id", j.start.path("round_id").asText())
                        .put("boot_id", boot)
                        .put("status", j.status)
                        .put("phase", j.phase)
                        .put("foreground", j.phase.equals("FOREGROUND"))
                        .put("slot_released", j.released)
                        .put("last_delivery_seq", j.delivery.get())
                        .put("undelivered_count", 0)
                        .put("updated_seq", j.delivery.get() + 10)
                        .putNull("lanes");
        v.putArray("uncertain_invocations");
        var counts = v.putObject("counters");
        for (String key :
                List.of(
                        "researched",
                        "qualified",
                        "shortlisted",
                        "ready",
                        "long_ready",
                        "long_generating")) counts.put(key, 0);
        return v;
    }

    private void reply(HttpExchange e, String type, JsonNode value, String request, int status)
            throws Exception {
        var message = envelope(type, value, request);
        if (!wire.valid(message)) throw new IllegalStateException("SYNTHETIC_INVALID_" + type);
        byte[] bytes = JSON.writeValueAsBytes(message);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, bytes.length);
        e.getResponseBody().write(bytes);
    }

    private void handle(HttpExchange e) throws java.io.IOException {
        String request = UUID.randomUUID().toString();
        try {
            if (!("Bearer " + TOKEN).equals(e.getRequestHeaders().getFirst("Authorization"))) {
                e.sendResponseHeaders(401, -1);
                return;
            }
            var bytes = e.getRequestBody().readAllBytes();
            var message = bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
            request = message.path("request_id").asText(request);
            String path = e.getRequestURI().getPath();
            if (path.endsWith("/runtime")) {
                var r =
                        JSON.createObjectNode()
                                .put("boot_id", boot)
                                .put("status", "READY")
                                .put("execution_capacity", 3);
                var active = r.putArray("active");
                jobs.values().stream().filter(j -> !j.released).forEach(j -> active.add(view(j)));
                reply(e, "RuntimeView", r, request, 200);
                return;
            }
            var pieces = path.split("/");
            String id = pieces[4];
            var job = jobs.get(id);
            if (path.endsWith("/admission")) {
                var query = new HashMap<String, String>();
                for (String item : e.getRequestURI().getRawQuery().split("&")) {
                    var pair = item.split("=", 2);
                    query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
                var r =
                        JSON.createObjectNode()
                                .put("execution_id", id)
                                .put("boot_id", boot)
                                .put("payload_digest", query.get("payload_digest"))
                                .put(
                                        "status",
                                        job == null
                                                ? "NOT_ACCEPTED"
                                                : job.released ? "TERMINAL" : "ACTIVE")
                                .put("dedup_until", query.get("original_deadline"));
                r.set("execution", job == null ? JSON.nullNode() : view(job));
                reply(e, "ExecutionAdmission", r, request, 200);
                return;
            }
            if (path.endsWith("/cancellation")) {
                if (job == null) throw new IllegalStateException("EXECUTION_GONE");
                job.cancelled = true;
                try {
                    job.done.get(2, TimeUnit.SECONDS);
                } catch (TimeoutException ignored) {
                    job.status = "CLOSING";
                }
                reply(e, "ExecutionView", view(job), request, 200);
                return;
            }
            if (path.contains("/commands/")) {
                if (job == null) throw new IllegalStateException("EXECUTION_GONE");
                var body = message.path("payload");
                String command = body.path("command_id").asText();
                String old = job.commands.putIfAbsent(command, digest(body));
                if (old != null && !old.equals(digest(body)))
                    throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
                if (body.path("kind").asText().equals("DISPLAY_STATE"))
                    job.display = body.path("payload");
                reply(
                        e,
                        "Acknowledgement",
                        JSON.createObjectNode().put("accepted", true),
                        request,
                        200);
                return;
            }
            if (e.getRequestMethod().equals("PUT")) {
                if (!wire.valid(message)) throw new IllegalStateException("INVALID_PAYLOAD");
                var start = (ObjectNode) message.path("payload");
                sends.incrementAndGet();
                synchronized (jobs) {
                    job = jobs.get(id);
                    if (job == null) {
                        if (jobs.values().stream().filter(j -> !j.released).count() >= 3)
                            throw new IllegalStateException("EXECUTOR_BUSY");
                        job =
                                new Job(
                                        start.deepCopy(),
                                        basicNext.getAndSet(false),
                                        failLongNext.getAndSet(false));
                        jobs.put(id, job);
                        var running = job;
                        workers.submit(() -> drive(running));
                    } else if (!job.start
                            .path("payload_digest")
                            .equals(start.path("payload_digest")))
                        throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
                }
                reply(e, "ExecutionView", view(job), request, 201);
                return;
            }
            throw new IllegalStateException("INVALID_PAYLOAD");
        } catch (Exception failure) {
            try {
                reply(
                        e,
                        "Error",
                        JSON.createObjectNode()
                                .put("code", "DEPENDENCY_UNAVAILABLE")
                                .put("retry_disposition", "STOP")
                                .put("reason", "synthetic"),
                        request,
                        503);
            } catch (Exception ignored) {
                e.sendResponseHeaders(500, -1);
            }
        } finally {
            e.close();
        }
    }

    private JsonNode control(Job j, String operation, String type, JsonNode payload)
            throws Exception {
        if (j.cancelled) throw new CancellationException();
        String path = "/executions/" + j.id() + "/" + operation, method = "POST";
        if (operation.equals("delivery")) {
            path =
                    "/executions/"
                            + j.id()
                            + "/deliveries/"
                            + URLEncoder.encode(
                                    payload.path("delivery_id").asText(), StandardCharsets.UTF_8);
            method = "PUT";
        }
        if (operation.equals("usage-receipt")) {
            path =
                    "/invocations/"
                            + URLEncoder.encode(
                                    payload.path("invocation_id").asText(), StandardCharsets.UTF_8)
                            + "/usage-receipts/"
                            + URLEncoder.encode(
                                    payload.path("receipt_id").asText(), StandardCharsets.UTF_8);
            method = "PUT";
        }
        String request = UUID.randomUUID().toString();
        var message = envelope(type, payload, request);
        if (!wire.valid(message)) throw new IllegalStateException("INVALID_SYNTHETIC_" + type);
        var http =
                HttpRequest.newBuilder(URI.create(controlBase + "/internal/v1" + path))
                        .timeout(Duration.ofSeconds(8))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Auralis-Audience", "coordinator")
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofByteArray(
                                        JSON.writeValueAsBytes(message)))
                        .build();
        var response = client.send(http, HttpResponse.BodyHandlers.ofByteArray());
        var decoded = JSON.readTree(response.body());
        if (response.statusCode() >= 400)
            throw new IllegalStateException(
                    "CONTROL_"
                            + operation
                            + ":"
                            + payload.path("kind").asText()
                            + ":"
                            + response.statusCode()
                            + "_"
                            + decoded.path("payload").path("code").asText());
        if (!wire.valid(decoded)) throw new IllegalStateException("INVALID_CONTROL_RESPONSE");
        return decoded.path("payload");
    }

    private JsonNode deliver(Job j, String kind, JsonNode payload, String run) throws Exception {
        var d =
                JSON.createObjectNode()
                        .put("delivery_id", j.id() + ":delivery:" + j.delivery.incrementAndGet())
                        .put("kind", kind)
                        .put("round_id", j.start.path("round_id").asText())
                        .put("run_id", run);
        d.set("binding", j.bundle == null ? JSON.nullNode() : j.bundle.path("binding"));
        d.putObject("versions").putArray("prompt_versions").add("synthetic");
        ((ObjectNode) d.path("versions"))
                .putArray("model_binding_refs")
                .add(j.start.path("model_bindings").path("write").path("ref").asText());
        d.set("payload", payload);
        d.put("payload_digest", digest(payload));
        var result = control(j, "delivery", "Delivery", d);
        for (var item : result.path("items"))
            if (item.path("decision").asText().equals("REJECTED"))
                throw new IllegalStateException("REJECTED_SYNTHETIC_CARD_" + item);
        j.display = result.path("display_state");
        return result;
    }

    private String call(Job j, String purpose, String candidate) throws Exception {
        String role =
                purpose.equals("SCREEN")
                        ? "screen"
                        : purpose.equals("WRITE_SHORT")
                                ? "write"
                                : purpose.equals("WRITE_LONG") ? "long_write" : "select";
        var binding = j.start.path("model_bindings").path(role);
        if (binding.isMissingNode()) binding = j.start.path("model_bindings").path("write");
        String id = j.id() + ":call:" + j.calls.incrementAndGet();
        var request = JSON.createObjectNode();
        var p =
                request.putArray("items")
                        .addObject()
                        .put("invocation_id", id)
                        .put("attempt", 1)
                        .put("purpose", purpose)
                        .put("cost_class", "MODEL")
                        .put("lane", purpose.equals("WRITE_LONG") ? "BACKGROUND" : "FOREGROUND")
                        .put(
                                "run_id",
                                purpose.equals("WRITE_LONG")
                                        ? null
                                        : j.start.path("run_id").asText())
                        .put("candidate_id", candidate)
                        .put("model_binding_ref", binding.path("ref").asText())
                        .put("max_input_tokens", 100)
                        .put("max_output_tokens", 100)
                        .put("input_digest", digest(JSON.getNodeFactory().textNode("synthetic")));
        var reads = p.putArray("read_ids");
        if (candidate == null) j.reads.values().stream().sorted().forEach(reads::add);
        else reads.add(j.reads.get(candidate));
        var grant =
                control(j, "invocation-grants", "InvocationGrants", request).path("items").get(0);
        if (!grant.path("decision").asText().equals("GRANTED"))
            throw new IllegalStateException("DENIED_" + grant.path("deny_reason").asText());
        modelCalls.incrementAndGet();
        var usage =
                JSON.createObjectNode()
                        .put("invocation_id", id)
                        .put("receipt_id", id + ":usage")
                        .put("outcome", "COMPLETED")
                        .put("finish", "COMPLETE")
                        .put("input_tokens", 5)
                        .put("cached_input_tokens", 0)
                        .put("output_tokens", 10)
                        .putNull("provider_request_ref");
        control(j, "usage-receipt", "UsageReceipt", usage);
        return id;
    }

    private void read(Job j, String candidate) throws Exception {
        String id = UUID.randomUUID().toString(),
                url = "https://example.com/synthetic/" + candidate;
        j.reads.put(candidate, id);
        var request =
                JSON.createObjectNode()
                        .put("read_id", id)
                        .put("candidate_id", candidate)
                        .put("url", url)
                        .put("purpose", "SCREEN_RESEARCH");
        control(j, "read-authorization", "ReadAuthorization", request);
        var receipt =
                JSON.createObjectNode()
                        .put("read_id", id)
                        .put("candidate_id", candidate)
                        .put("purpose", "SCREEN_RESEARCH")
                        .put("requested_url", url)
                        .put("final_url", url)
                        .put("publisher_key", "example.com")
                        .put("status", "READ_OK")
                        .putNull("failure_reason")
                        .put(
                                "read_at",
                                Instant.now()
                                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                        .toString())
                        .put(
                                "content_digest",
                                digest(JSON.getNodeFactory().textNode("synthetic body")))
                        .put(
                                "excerpt_digest",
                                digest(JSON.getNodeFactory().textNode("synthetic excerpt")))
                        .put("text_chars", 100)
                        .putNull("near_duplicate_of")
                        .put("retrieval_mode", "NETWORK");
        receipt.putObject("checks").put("mentions_title", true).put("mentions_artist", true);
        var payload = JSON.createObjectNode();
        payload.putArray("items").add(receipt);
        control(j, "read-receipts", "ReadReceipts", payload);
    }

    private JsonNode preferences(Job j) throws Exception {
        var accepted = j.start.path("input").path("accepted_preferences");
        if (!accepted.isNull()) return accepted;
        var raw = j.start.path("input").path("raw");
        var p =
                JSON.createObjectNode()
                        .put("input_digest", raw.path("input_digest").asText())
                        .put("reference_revision", settings.path("reference_revision").asText())
                        .put("parser_revision", settings.path("parser_revisions").get(0).asText());
        for (String key :
                List.of("preferences", "constraints", "unresolved_constraints", "soft_preferences"))
            p.putArray(key);
        p.put("proposal_digest", contentDigest(p, "proposal_digest"));
        return control(j, "preference-resolution", "PreferenceResolutionProposal", p);
    }

    private JsonNode recall(Job j, JsonNode pref) throws Exception {
        var excluded = new HashSet<String>();
        j.start
                .path("input")
                .path("exclusions")
                .forEach(n -> excluded.add(n.path("release_group_id").asText()));
        int wanted = j.start.path("selection_config").path("recall_target_count").asInt();
        var ids =
                albums.stream()
                        .filter(id -> !excluded.contains(id))
                        .limit(Math.min(wanted, recallLimit))
                        .toList();
        var p =
                JSON.createObjectNode()
                        .put("input_digest", pref.path("input_digest").asText())
                        .put(
                                "effective_preference_digest",
                                pref.path("effective_preference_digest").asText())
                        .put(
                                "exclusion_digest",
                                j.start.path("input").path("exclusion_digest").asText())
                        .put("requested_count", wanted)
                        .put("returned_count", ids.size())
                        .put("execution_status", ids.isEmpty() ? "NO_COVERAGE" : "COMPLETE")
                        .put("raw_reason", "synthetic")
                        .put("shortfall", wanted - ids.size())
                        .put("truncated", false);
        p.set("recall_binding", j.start.path("recall_binding"));
        var candidates = p.putArray("candidates");
        for (int n = 0; n < ids.size(); n++)
            candidates.addObject().put("release_group_id", ids.get(n)).put("recall_rank", n + 1);
        p.putArray("source_dependencies")
                .add(
                        "audience:"
                                + settings.path("audience_binding")
                                        .path("snapshotDigest")
                                        .asText());
        p.put("proposal_digest", contentDigest(p, "proposal_digest"));
        return control(j, "candidate-validation", "RecallProposal", p);
    }

    private ObjectNode batch(
            Job j, List<String> ids, String screen, String written, String selected) {
        String mode =
                j.start.path("operation").asText().equals("CURATED_REVIEWS")
                        ? "CURATED"
                        : j.basic ? "BASIC" : "PERSONALIZED";
        int target = j.start.path("selection_config").path("recommendation_target_count").asInt();
        var p =
                JSON.createObjectNode()
                        .put("batch_kind", "FIRST")
                        .put("outcome", ids.isEmpty() ? "NO_MATCH" : "DISPLAYABLE")
                        .put("reason_code", ids.isEmpty() ? "RECALL_NO_COVERAGE" : null)
                        .put("analysis_mode", mode)
                        .putNull("timing");
        p.putArray("input_issues");
        var selection =
                p.putObject("selection")
                        .put("status", selected == null ? "NOT_RUN" : "COMPLETED")
                        .put("invocation_id", selected);
        var order = selection.putArray("order");
        var screened = p.putArray("screen_order");
        var cards = p.putArray("cards");
        var screenings = p.putArray("screenings");
        for (int n = 0; n < ids.size(); n++) {
            String id = ids.get(n), read = j.reads.get(id);
            screened.add(id);
            if (selected != null)
                order.addObject().put("candidate_id", id).put("verdict", "recommend");
            var r =
                    screenings
                            .addObject()
                            .put("candidate_id", id)
                            .put("recall_rank", n + 1)
                            .put("research_status", "QUALIFIED_READS")
                            .put("disposition", "SHORTLISTED")
                            .putNull("reason_code")
                            .putNull("brief");
            r.putArray("qualified_read_ids").add(read);
            r.putArray("read_assessments")
                    .addObject()
                    .put("read_id", read)
                    .put("deterministic_pass", true)
                    .put("about_target", "yes")
                    .put("substantive", "yes");
            r.putObject("screen")
                    .put("status", "OK")
                    .put("score", 5)
                    .put("invocation_id", screen)
                    .put("prompt_version", "synthetic");
            var card =
                    cards.addObject()
                            .put("candidate_id", id)
                            .put(
                                    "selection_basis",
                                    mode.equals("CURATED")
                                            ? "CURATED"
                                            : mode.equals("BASIC") ? "SCREEN_ONLY" : "SELECTED")
                            .put("long_review_state", n < target ? "GENERATING" : "NOT_STARTED");
            card.putObject("short_review")
                    .put("text", "这张专辑以细腻的节奏和清晰的旋律展开，层次之间保持自然的呼吸感，适合在安静的环境里从头听到结尾。")
                    .put("invocation_id", written)
                    .put("prompt_version", "synthetic")
                    .putArray("citations")
                    .addObject()
                    .put("marker", "R1")
                    .put("read_id", read);
            card.putObject("admission")
                    .put("screen_score", 5)
                    .put("screen_invocation_id", screen)
                    .putArray("qualified_read_ids")
                    .add(read);
        }
        p.putObject("pending")
                .put("long_reviews_generating", Math.min(target, ids.size()))
                .put("deferred_candidates", 0)
                .put("supply_state", "R_EXHAUSTED");
        return p;
    }

    private void longReview(Job j, String candidate) throws Exception {
        if (j.failLong && j.longReady == 0)
            throw new IllegalStateException("SYNTHETIC_LONG_FAILURE");
        String call = call(j, "WRITE_LONG", candidate);
        var p =
                JSON.createObjectNode()
                        .put("candidate_id", candidate)
                        .put("status", "READY")
                        .put("han_char_count", 24)
                        .put("prompt_version", "synthetic")
                        .putNull("reason_code");
        p.putObject("sections")
                .put("production", "制作保留了空间感，乐器之间能听见清楚的层次。")
                .put("instrumentation", "旋律与节奏交替推进。")
                .put("vocals", "")
                .put("mood", "整体气氛放松而有变化。");
        p.putArray("citations")
                .addObject()
                .put("marker", "R1")
                .put("read_id", j.reads.get(candidate));
        p.putArray("extra_read_ids");
        p.putArray("missing_sections");
        p.putArray("invocation_ids").add(call);
        deliver(j, "LONG_REVIEW", p, null);
        j.longReady++;
    }

    private void report(Job j, String trigger) throws Exception {
        while (holdReports && !j.cancelled) Thread.sleep(10);
        if (j.cancelled) throw new CancellationException();
        var p =
                JSON.createObjectNode()
                        .put("close_reason", "DRAINED")
                        .put("elapsed_ms", 1)
                        .putNull("lanes")
                        .putNull("structure_repairs")
                        .putNull("cached_input_tokens")
                        .putNull("input_tokens")
                        .put("trigger_reason", trigger);
        var counts = p.putObject("counts");
        for (String key :
                List.of(
                        "researched",
                        "qualified",
                        "shortlisted",
                        "ready",
                        "long_ready",
                        "ineligible",
                        "below_floor",
                        "unresearched",
                        "reserve",
                        "shown")) counts.put(key, 0);
        deliver(j, "ROUND_REPORT", p, null);
    }

    private void drive(Job j) {
        try {
            j.status = "RUNNING";
            while (hold && !j.cancelled) Thread.sleep(10);
            if (j.cancelled) throw new CancellationException();
            if (j.start.path("start_mode").asText().equals("CONTINUE")) {
                j.bundle = j.start.path("continuation").path("candidate_bundle");
                j.display = j.start.path("continuation").path("display_state");
                for (var item : j.start.path("continuation").path("candidates"))
                    if (!item.path("read_refs").isEmpty())
                        j.reads.put(
                                item.path("release_group_id").asText(),
                                item.path("read_refs").get(0).path("read_id").asText());
                var regenerate =
                        j.start
                                .path("continuation")
                                .path("pending")
                                .path("regenerate_long_review_ids");
                if (!regenerate.isEmpty()) {
                    j.phase = "BACKGROUND";
                    for (var id : regenerate) longReview(j, id.asText());
                    report(j, null);
                } else {
                    var fresh = new ArrayList<String>();
                    for (var item : j.start.path("continuation").path("candidates"))
                        if (Set.of("UNRESEARCHED", "RESERVE").contains(item.path("state").asText()))
                            fresh.add(item.path("release_group_id").asText());
                    int target =
                            j.start
                                    .path("selection_config")
                                    .path("recommendation_target_count")
                                    .asInt();
                    fresh = new ArrayList<>(fresh.subList(0, Math.min(target, fresh.size())));
                    if (!fresh.isEmpty()) {
                        for (String id : fresh) if (!j.reads.containsKey(id)) read(j, id);
                        String screen = call(j, "SCREEN", null),
                                written = call(j, "WRITE_SHORT", null),
                                selected = call(j, "SELECT", null);
                        var batch = batch(j, fresh, screen, written, selected);
                        batch.put("batch_kind", "SUPPLY");
                        deliver(
                                j,
                                "CARD_BATCH",
                                batch,
                                j.start.path("run_kind").asText().equals("RESCREEN")
                                        ? null
                                        : j.start.path("run_id").asText());
                    }
                    if (j.start.path("run_kind").asText().equals("RESCREEN")) {
                        String selected = call(j, "SELECT", null);
                        var p =
                                JSON.createObjectNode()
                                        .put(
                                                "basis_display_seq",
                                                j.display.path("display_seq").asLong())
                                        .put("run_id", j.start.path("run_id").asText())
                                        .put("analysis_mode", "PERSONALIZED")
                                        .putNull("timing");
                        var selection =
                                p.putObject("selection")
                                        .put("status", "COMPLETED")
                                        .put("invocation_id", selected);
                        var order = selection.putArray("order");
                        var chosen = p.putArray("order");
                        for (var id : j.display.path("ready_unshown_ids")) {
                            order.addObject()
                                    .put("candidate_id", id.asText())
                                    .put("verdict", "recommend");
                            chosen.addObject()
                                    .put("candidate_id", id.asText())
                                    .put("verdict", "recommend");
                        }
                        deliver(j, "ORDER_UPDATE", p, j.start.path("run_id").asText());
                    }
                    j.phase = "BACKGROUND";
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(longDelayMs);
                    while (System.nanoTime() < until && !j.cancelled) Thread.sleep(10);
                    for (String id : fresh) longReview(j, id);
                    report(j, null);
                }
            } else {
                var pref = preferences(j);
                if (!pref.path("has_positive_signal").asBoolean()
                        && !j.start.path("operation").asText().equals("CURATED_REVIEWS")) {
                    report(j, "JAVA_CURATED");
                    j.status = "CLOSED";
                    return;
                }
                j.bundle =
                        j.start.path("operation").asText().equals("CURATED_REVIEWS")
                                ? j.start.path("curated_candidates")
                                : recall(j, pref);
                var ids = new ArrayList<String>();
                j.bundle
                        .path("accepted")
                        .forEach(n -> ids.add(n.path("release_group_id").asText()));
                for (String id : ids) read(j, id);
                String screen = ids.isEmpty() ? null : call(j, "SCREEN", null),
                        written = ids.isEmpty() ? null : call(j, "WRITE_SHORT", null),
                        selected =
                                ids.isEmpty()
                                                || j.basic
                                                || j.start
                                                        .path("operation")
                                                        .asText()
                                                        .equals("CURATED_REVIEWS")
                                        ? null
                                        : call(j, "SELECT", null);
                int target =
                        j.start
                                .path("selection_config")
                                .path("recommendation_target_count")
                                .asInt();
                var first = ids.subList(0, Math.min(target, ids.size()));
                var firstBatch = batch(j, first, screen, written, selected);
                if (first.size() < ids.size())
                    ((ObjectNode) firstBatch.path("pending")).put("supply_state", "PAUSED");
                var result = deliver(j, "CARD_BATCH", firstBatch, j.start.path("run_id").asText());
                j.phase = "BACKGROUND";
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(longDelayMs);
                while (System.nanoTime() < until && !j.cancelled) Thread.sleep(10);
                for (var id : result.path("published_candidate_ids")) longReview(j, id.asText());
                report(j, null);
            }
            j.status = "CLOSED";
        } catch (CancellationException ignored) {
            j.status = "CANCELLED";
        } catch (Exception failure) {
            j.status = j.cancelled ? "CANCELLED" : "FAILED";
            if (!j.cancelled) failures.add(failure.getMessage());
        } finally {
            j.released = true;
            j.phase = "IDLE";
            j.done.complete(null);
        }
    }

    void cancelAll() throws Exception {
        for (var j : jobs.values()) j.cancelled = true;
        for (var j : jobs.values()) j.done.get(10, TimeUnit.SECONDS);
    }

    public void close() throws Exception {
        cancelAll();
        server.stop(0);
        workers.shutdownNow();
        httpWorkers.shutdownNow();
        client.shutdownNow();
    }
}
