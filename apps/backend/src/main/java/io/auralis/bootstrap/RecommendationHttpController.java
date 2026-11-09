package io.auralis.bootstrap;

import static io.auralis.contracts.CanonicalJson.JSON;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralis.catalog.api.CatalogFailure;
import io.auralis.discovery.application.Recommendations;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.preference.api.PreferenceRejected;
import jakarta.servlet.http.*;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class RecommendationHttpController {
    private final Recommendations recommendations;
    private final HttpErrors errors;

    public RecommendationHttpController(Recommendations recommendations, HttpErrors errors) {
        this.recommendations = recommendations;
        this.errors = errors;
    }

    private String body(HttpServletRequest request) throws java.io.IOException {
        var bytes = request.getInputStream().readNBytes(4 * 1024 * 1024 + 1);
        if (bytes.length > 4 * 1024 * 1024) throw new PersistenceRejected("INVALID_PAYLOAD");
        var value = JSON.readTree(bytes);
        if (value == null) throw new PersistenceRejected("INVALID_PAYLOAD");
        return value.toString();
    }

    private ResponseEntity<JsonNode> result(String text) throws java.io.IOException {
        var node = JSON.readTree(text);
        return ResponseEntity.status(
                        (Set.of("WAITING", "RUNNING").contains(node.path("status").asText())
                                        || java.util.stream.StreamSupport.stream(
                                                        node.path("slots").spliterator(), false)
                                                .anyMatch(
                                                        slot ->
                                                                slot.path("status")
                                                                        .asText()
                                                                        .equals("SUPPLYING")))
                                ? 202
                                : 200)
                .body(node);
    }

    @PostMapping("/v1/discovery-sessions")
    public ResponseEntity<JsonNode> submit(HttpServletRequest request) throws java.io.IOException {
        return result(
                recommendations.submit(
                        SubjectCookies.credential(request),
                        request.getHeader("X-CSRF-Token"),
                        request.getHeader("Idempotency-Key"),
                        body(request)));
    }

    private ResponseEntity<JsonNode> action(
            HttpServletRequest request, String session, String operation, String target)
            throws java.io.IOException {
        return result(
                recommendations.action(
                        SubjectCookies.credential(request),
                        request.getHeader("X-CSRF-Token"),
                        request.getHeader("Idempotency-Key"),
                        session,
                        operation,
                        target,
                        body(request)));
    }

    @PostMapping("/v1/discovery-sessions/{session_id}/cards/{slot_id}/actions")
    public ResponseEntity<JsonNode> card(
            @PathVariable("session_id") String session,
            @PathVariable("slot_id") String slot,
            HttpServletRequest request)
            throws java.io.IOException {
        return action(request, session, "CARD", slot);
    }

    @PostMapping("/v1/discovery-sessions/{session_id}/next-batch")
    public ResponseEntity<JsonNode> batch(
            @PathVariable("session_id") String session, HttpServletRequest request)
            throws java.io.IOException {
        return action(request, session, "BATCH", null);
    }

    @PostMapping("/v1/discovery-sessions/{session_id}/rescreen")
    public ResponseEntity<JsonNode> rescreen(
            @PathVariable("session_id") String session, HttpServletRequest request)
            throws java.io.IOException {
        return action(request, session, "RESCREEN", null);
    }

    @GetMapping("/v1/discovery-sessions/{session_id}/long-reviews/{candidate_id}")
    public JsonNode review(
            @PathVariable("session_id") String session,
            @PathVariable("candidate_id") String candidate,
            HttpServletRequest request)
            throws java.io.IOException {
        return JSON.readTree(
                recommendations.longReview(SubjectCookies.credential(request), session, candidate));
    }

    @PostMapping("/v1/discovery-sessions/{session_id}/long-reviews/{candidate_id}/open")
    public ResponseEntity<JsonNode> open(
            @PathVariable("session_id") String session,
            @PathVariable("candidate_id") String candidate,
            HttpServletRequest request)
            throws java.io.IOException {
        return action(request, session, "OPEN", candidate);
    }

    @PostMapping("/v1/discovery-sessions/{session_id}/long-reviews/{candidate_id}/regenerate")
    public ResponseEntity<JsonNode> regenerate(
            @PathVariable("session_id") String session,
            @PathVariable("candidate_id") String candidate,
            HttpServletRequest request)
            throws java.io.IOException {
        return action(request, session, "REGENERATE", candidate);
    }

    @ExceptionHandler({
        PersistenceRejected.class,
        CatalogFailure.class,
        PreferenceRejected.class,
        java.io.IOException.class,
        IllegalArgumentException.class
    })
    public void rejected(
            Exception failure, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        String code =
                failure instanceof PersistenceRejected
                                || failure instanceof PreferenceRejected
                                || failure instanceof CatalogFailure
                        ? failure.getMessage()
                        : "INVALID_PAYLOAD";
        int status =
                code.equals("QUEUE_FULL")
                        ? 429
                        : code.equals("EXPIRED")
                                ? 400
                                : code.equals("FORBIDDEN")
                                        ? 400
                                        : code.contains("CONFLICT") || code.equals("EXECUTOR_BUSY")
                                                ? 409
                                                : Set.of(
                                                                        "RECOMMENDATION_UNAVAILABLE",
                                                                        "INVALID_EXECUTION_CONFIGURATION",
                                                                        "CURATED_UNAVAILABLE",
                                                                        "MAINTENANCE",
                                                                        "CATALOG_VERSION_CHANGED")
                                                                .contains(code)
                                                        ? 503
                                                        : 400;
        if (failure instanceof CatalogFailure catalog) status = catalog.status() >= 500 ? 503 : 400;
        errors.write(request, response, status, code);
    }
}
