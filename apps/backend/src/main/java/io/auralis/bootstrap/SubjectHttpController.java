package io.auralis.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.session.api.SubjectSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.web.bind.annotation.*;

@RestController
public class SubjectHttpController {
    private final SubjectSession subjects;
    private final ObjectMapper json;
    private final Clock clock;
    private final OnlineProperties properties;
    private final WireProtocol protocol;

    public SubjectHttpController(
            SubjectSession subjects,
            ObjectMapper json,
            Clock clock,
            OnlineProperties properties,
            WireProtocol protocol) {
        this.subjects = subjects;
        this.json = json;
        this.clock = clock;
        this.properties = properties;
        this.protocol = protocol;
    }

    @PostMapping(
            value = "/v1/bootstrap",
            consumes = "application/json",
            produces = "application/json")
    public JsonNode bootstrap(HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        byte[] bytes = request.getInputStream().readNBytes(1025);
        if (bytes.length > 1024) throw new PersistenceRejected("INVALID_PAYLOAD");
        JsonNode input;
        try {
            input =
                    json.reader()
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(bytes);
        } catch (java.io.IOException invalid) {
            throw new PersistenceRejected("INVALID_PAYLOAD");
        }
        if (input == null || !protocol.validDefinition("BootstrapRequest", input))
            throw new PersistenceRejected("INVALID_PAYLOAD");
        String token;
        try {
            token = SubjectCookies.credential(request);
        } catch (IllegalArgumentException invalid) {
            throw new PersistenceRejected("FORBIDDEN");
        }
        var result = subjects.bootstrap(token);
        response.addHeader(
                "Set-Cookie",
                SubjectCookies.header(
                        result.credential(), result.expiresAt(), clock.instant(), properties));
        var body = json.createObjectNode().put("csrf_token", result.csrf());
        body.set("state", json.readTree(result.state()));
        return body;
    }

    @GetMapping(value = "/v1/current-state", produces = "application/json")
    public JsonNode current(HttpServletRequest request) throws Exception {
        return json.readTree(subjects.restore(SubjectCookies.credential(request), null));
    }

    @GetMapping(value = "/v1/discovery-sessions/{sessionId}", produces = "application/json")
    public JsonNode session(@PathVariable String sessionId, HttpServletRequest request)
            throws Exception {
        return json.readTree(subjects.restore(SubjectCookies.credential(request), sessionId));
    }
}
