package io.auralis.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public final class HttpErrors implements ErrorController {
    private final ObjectMapper mapper;
    private final WireProtocol protocol;

    public HttpErrors(ObjectMapper mapper, WireProtocol protocol) {
        this.mapper = mapper;
        this.protocol = protocol;
    }

    public void write(
            HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        String id = UUID.randomUUID().toString();
        if (request.getAttribute("auralis.internal.request_id") instanceof String requestId)
            id = requestId;
        Map<String, Object> body = new LinkedHashMap<>();
        Object original = request.getAttribute("jakarta.servlet.error.request_uri");
        String path = original instanceof String uri ? uri : request.getRequestURI();
        if (path.startsWith("/internal/")) {
            body.put("schema_version", "intel.v1");
            body.put("contract_revision", protocol.revision());
            body.put("request_id", id);
            body.put(
                    "trace_context",
                    Map.of("trace_id", id, "span_id", id.substring(0, Math.min(16, id.length()))));
            body.put("message_type", "Error");
            body.put("payload", Map.of("code", code, "retry_disposition", "STOP", "reason", code));
        } else {
            body.put("code", code);
            body.put("request_id", id);
            body.put("message_key", code);
            boolean catalogRetry =
                    code.startsWith("CATALOG_")
                            && (status == 429 || status == 503 || status == 504);
            boolean retry =
                    catalogRetry
                            || code.equals("QUEUE_FULL")
                            || code.equals("MAINTENANCE")
                            || code.equals("RECOMMENDATION_UNAVAILABLE");
            body.put("retryable", retry);
            body.put("idempotency_consumed", false);
            body.put("current_revision", null);
            body.put(
                    "retry_disposition",
                    retry
                            ? "RETRY_SAME_KEY"
                            : code.equals("REVISION_CONFLICT") ? "POLL_STATE" : "STOP");
        }
        mapper.writeValue(response.getOutputStream(), body);
    }

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object value = request.getAttribute("jakarta.servlet.error.status_code");
        int status = value instanceof Integer number ? number : 500;
        write(
                request,
                response,
                status,
                status == 404 ? "INVALID_PAYLOAD" : "DEPENDENCY_UNAVAILABLE");
    }
}
