package io.auralisy.bootstrap;

import static io.auralisy.contracts.CanonicalJson.JSON;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralisy.budget.api.BudgetAuthority;
import io.auralisy.discovery.api.DispatchQueue;
import io.auralisy.evidence.api.EvidenceAuthority;
import io.auralisy.persistence.PersistenceRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Authenticated internal control only; browser recommendation commands belong to BE-003. */
@RestController
public class ExecutionControlController {
    private final io.auralisy.persistence.ShortTransactions tx;
    private final DispatchQueue queue;
    private final BudgetAuthority budget;
    private final EvidenceAuthority evidence;
    private final WireProtocol wire;
    private final HttpErrors errors;

    public ExecutionControlController(
            DispatchQueue queue,
            BudgetAuthority budget,
            EvidenceAuthority evidence,
            WireProtocol wire,
            HttpErrors errors,
            io.auralisy.discovery.application.Recommendations recommendations,
            io.auralisy.persistence.ShortTransactions tx) {
        this.tx = tx;
        this.queue = queue;
        this.budget = budget;
        this.evidence = evidence;
        this.wire = wire;
        this.errors = errors;
        this.recommendations = recommendations;
    }

    private JsonNode read(HttpServletRequest request, String expected) throws java.io.IOException {
        byte[] bytes = request.getInputStream().readNBytes(4 * 1024 * 1024 + 1);
        if (bytes.length > 4 * 1024 * 1024) throw new PersistenceRejected("INVALID_PAYLOAD");
        var n = JSON.readTree(bytes);
        if (!wire.valid(n) || !n.path("message_type").asText().equals(expected))
            throw new PersistenceRejected("INVALID_PAYLOAD");
        request.setAttribute("auralis.internal.request_id", n.path("request_id").asText());
        return n.path("payload");
    }

    private JsonNode response(HttpServletRequest request, String kind, String payload)
            throws java.io.IOException {
        String id = (String) request.getAttribute("auralis.internal.request_id");
        if (id == null) id = UUID.randomUUID().toString();
        var n =
                JSON.createObjectNode()
                        .put("schema_version", "intel.v1")
                        .put("contract_revision", wire.revision())
                        .put("request_id", id)
                        .put("message_type", kind);
        n.putObject("trace_context")
                .put("trace_id", id)
                .put("span_id", id.substring(0, Math.min(16, id.length())));
        n.set("payload", JSON.readTree(payload));
        if (!wire.valid(n)) throw new IllegalStateException("INVALID_CONTROL_RESPONSE");
        return n;
    }

    @PostMapping("/internal/v1/executions/{execution}/invocation-grants")
    public JsonNode grants(@PathVariable String execution, HttpServletRequest request)
            throws java.io.IOException {
        var p = read(request, "InvocationGrants");
        return response(
                request,
                "GrantResults",
                tx.write(() -> budget.grants(queue.context(execution).budget(), p.toString())));
    }

    @GetMapping("/internal/v1/executions/{execution}/budget-state")
    public JsonNode state(@PathVariable String execution, HttpServletRequest request)
            throws java.io.IOException {
        return response(
                request,
                "BudgetState",
                tx.write(() -> budget.state(queue.context(execution).budget())));
    }

    @PutMapping("/internal/v1/invocations/{invocation}/usage-receipts/{receipt}")
    public JsonNode usage(
            @PathVariable String invocation,
            @PathVariable String receipt,
            HttpServletRequest request)
            throws java.io.IOException {
        var p = read(request, "UsageReceipt");
        if (!p.path("invocation_id").asText().equals(invocation)
                || !p.path("receipt_id").asText().equals(receipt)
                || !invocation.contains(":")) throw new PersistenceRejected("BINDING_MISMATCH");
        // Dispatcher generates UUID execution IDs. Budget independently verifies the retained
        // digest.
        String execution = invocation.substring(0, invocation.indexOf(':'));
        return response(request, "UsageSettlement", budget.settle(execution, p.toString()));
    }

    @PostMapping("/internal/v1/executions/{execution}/read-authorization")
    public JsonNode authorize(@PathVariable String execution, HttpServletRequest request)
            throws java.io.IOException {
        var p = read(request, "ReadAuthorization");
        return response(
                request,
                "ReadAuthorizationResult",
                tx.write(
                        () ->
                                evidence.authorize(
                                        queue.context(execution).evidence(), p.toString())));
    }

    @PostMapping("/internal/v1/executions/{execution}/read-receipts")
    public JsonNode receipts(@PathVariable String execution, HttpServletRequest request)
            throws java.io.IOException {
        var p = read(request, "ReadReceipts");
        return response(
                request,
                "ReadRegistrationResult",
                tx.write(
                        () ->
                                evidence.receipts(
                                        queue.context(execution).evidence(), p.toString())));
    }

    @GetMapping("/internal/v1/executions/{execution}/seed-evidence")
    public JsonNode seeds(@PathVariable String execution, HttpServletRequest request)
            throws java.io.IOException {
        return response(
                request,
                "SeedEvidenceState",
                tx.write(() -> evidence.seeds(queue.context(execution).evidence())));
    }

    @PutMapping("/internal/v1/executions/{execution}/seed-evidence")
    public JsonNode saveSeeds(@PathVariable String execution, HttpServletRequest request)
            throws java.io.IOException {
        var p = read(request, "SeedEvidenceUpdate");
        return response(
                request,
                "SeedEvidenceState",
                tx.write(
                        () ->
                                evidence.saveSeeds(
                                        queue.context(execution).evidence(), p.toString())));
    }

    @ExceptionHandler({
        PersistenceRejected.class,
        io.auralisy.preference.api.PreferenceRejected.class,
        java.io.IOException.class,
        IllegalArgumentException.class
    })
    public void rejected(Exception e, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        String code = e.getMessage();
        if (!Set.of(
                        "BINDING_MISMATCH",
                        "IDEMPOTENCY_CONFLICT",
                        "EXECUTION_GONE",
                        "MAINTENANCE",
                        "INVALID_PAYLOAD")
                .contains(code == null ? "" : code)) code = "INVALID_PAYLOAD";
        errors.write(
                request,
                response,
                code.equals("EXECUTION_GONE") ? 410 : code.equals("MAINTENANCE") ? 503 : 400,
                code);
    }

    private final io.auralisy.discovery.application.Recommendations recommendations;

    @PutMapping("/internal/v1/executions/{execution}/deliveries/{delivery}")
    public JsonNode delivery(
            @PathVariable String execution,
            @PathVariable String delivery,
            HttpServletRequest request)
            throws java.io.IOException {
        var payload = read(request, "Delivery");
        if (!delivery.equals(payload.path("delivery_id").asText()))
            throw new PersistenceRejected("BINDING_MISMATCH");
        return response(
                request, "DeliveryResult", recommendations.deliver(execution, payload.toString()));
    }

    @PostMapping("/internal/v1/executions/{execution}/preference-resolution")
    public JsonNode preferences(@PathVariable String execution, HttpServletRequest request)
            throws java.io.IOException {
        var payload = read(request, "PreferenceResolutionProposal");
        return response(
                request,
                "EffectivePreferenceSnapshot",
                recommendations.acceptPreferences(execution, payload.toString()));
    }

    @PostMapping("/internal/v1/executions/{execution}/candidate-validation")
    public JsonNode candidates(@PathVariable String execution, HttpServletRequest request)
            throws java.io.IOException {
        var payload = read(request, "RecallProposal");
        return response(
                request,
                "CandidateBundle",
                recommendations.acceptCandidates(execution, payload.toString()));
    }
}
