package io.auralis.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OnlineBootstrapTest {
    static final String TOKEN = "synthetic-service-token-for-bootstrap-tests";
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void healthyProbeIsCachedAndSlowBodyIsBounded() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var slow = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/runtime",
                exchange -> {
                    calls.incrementAndGet();
                    byte[] bytes =
                            JSON.writeValueAsBytes(
                                    java.util.Map.of(
                                            "schema_version",
                                            "intel.v1",
                                            "contract_revision",
                                            "0.27",
                                            "request_id",
                                            "probe",
                                            "trace_context",
                                            java.util.Map.of(
                                                    "trace_id", "probe", "span_id", "span"),
                                            "message_type",
                                            "RuntimeView",
                                            "payload",
                                            java.util.Map.of(
                                                    "boot_id",
                                                    "test",
                                                    "status",
                                                    "READY",
                                                    "execution_capacity",
                                                    1,
                                                    "active",
                                                    java.util.List.of())));
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var stream = exchange.getResponseBody()) {
                        if (slow.get()) {
                            try {
                                Thread.sleep(3000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        stream.write(bytes);
                    }
                });
        server.start();
        var p = properties();
        p.setPythonUrl("http://127.0.0.1:" + server.getAddress().getPort());
        try (var probe = new PythonHealthIndicator(p, new WireProtocol(JSON), JSON)) {
            probe.initialize();
            for (int n = 0; n < 5; n++) assertEquals("UP", probe.health().getStatus().getCode());
            assertEquals(1, calls.get());
            slow.set(true);
            assertTimeoutPreemptively(java.time.Duration.ofMillis(2800), probe::refresh);
            assertEquals("DOWN", probe.health().getStatus().getCode());
        } finally {
            server.stop(0);
        }
    }

    static OnlineProperties properties() {
        var p = new OnlineProperties();
        p.setJdbcUrl("jdbc:postgresql://127.0.0.1:15434/auralis_core_verify");
        p.setDatabaseUser("auralis_online");
        p.setDatabasePassword("synthetic-password");
        p.setProductOrigin("http://127.0.0.1:8080");
        p.setPythonUrl("http://127.0.0.1:1");
        p.setServiceToken(TOKEN);
        p.setContractRevision("0.27");
        return p;
    }

    @Test
    void configurationRejectsMissingSecretsForeignAddressesAndVersionsWithoutEchoing() {
        properties().validate("0.27");
        for (var field :
                java.util.List.of(
                        "password", "token", "python", "origin", "database", "revision")) {
            var p = properties();
            switch (field) {
                case "password" -> p.setDatabasePassword("");
                case "token" -> p.setServiceToken("secret\nheader-injection");
                case "python" -> p.setPythonUrl("http://example.com:8000");
                case "origin" -> p.setProductOrigin("https://example.com/path");
                case "database" -> p.setJdbcUrl("jdbc:postgresql://user:secret@localhost/research");
                case "revision" -> p.setContractRevision("0.25");
            }
            assertEquals(
                    "INVALID_ONLINE_CONFIGURATION",
                    assertThrows(IllegalArgumentException.class, () -> p.validate("0.27"))
                            .getMessage());
        }
    }

    @Test
    void internalAuthenticationAndProductOriginUseSafeSchemaErrors() throws Exception {
        var protocol = new WireProtocol(JSON);
        var boundary = new HttpBoundary(properties(), new HttpErrors(JSON, protocol));
        var request =
                new MockHttpServletRequest(
                        "POST", "/internal/v1/executions/test/preference-resolution");
        var response = new MockHttpServletResponse();
        boundary.doFilter(request, response, (a, b) -> fail("Unauthenticated request passed"));
        assertEquals(401, response.getStatus());
        assertTrue(protocol.valid(JSON.readTree(response.getContentAsByteArray())));
        request =
                new MockHttpServletRequest(
                        "POST", "/internal/v1/executions/test/preference-resolution");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("X-Auralis-Audience", "intelligence");
        response = new MockHttpServletResponse();
        boundary.doFilter(request, response, (a, b) -> fail("Wrong audience passed"));
        assertEquals(403, response.getStatus());
        assertFalse(response.getContentAsString().contains(TOKEN));
        request = new MockHttpServletRequest("POST", "/v1/bootstrap");
        request.addHeader("Origin", "https://attacker.invalid");
        response = new MockHttpServletResponse();
        boundary.doFilter(request, response, (a, b) -> fail("Cross origin passed"));
        assertEquals(403, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(protocol.validProductError(JSON.readTree(response.getContentAsByteArray())));
        request = new MockHttpServletRequest("POST", "/v1/bootstrap");
        request.addHeader("Origin", properties().getProductOrigin());
        response = new MockHttpServletResponse();
        boundary.doFilter(
                request,
                response,
                (a, b) -> ((jakarta.servlet.http.HttpServletResponse) b).setStatus(204));
        assertEquals(204, response.getStatus());
        request =
                new MockHttpServletRequest(
                        "POST", "/internal/v1/executions/test/preference-resolution");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("X-Auralis-Audience", "coordinator");
        response = new MockHttpServletResponse();
        boundary.doFilter(
                request,
                response,
                (a, b) -> ((jakarta.servlet.http.HttpServletResponse) b).setStatus(204));
        assertEquals(204, response.getStatus());
    }

    @Test
    void pythonUnavailableIsDownButIncompatibleProtocolFailsStartup() throws Exception {
        var p = properties();
        try (var probe = new PythonHealthIndicator(p, new WireProtocol(JSON), JSON)) {
            probe.initialize();
            assertEquals("DOWN", probe.health().getStatus().getCode());
        }
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/runtime",
                exchange -> {
                    assertEquals(
                            "Bearer " + TOKEN,
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] bytes = "{\"contract_revision\":\"0.25\"}".getBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(bytes);
                    }
                });
        server.start();
        p.setPythonUrl("http://127.0.0.1:" + server.getAddress().getPort());
        try (var probe = new PythonHealthIndicator(p, new WireProtocol(JSON), JSON)) {
            assertEquals(
                    "INCOMPATIBLE_PYTHON_PROTOCOL",
                    assertThrows(IllegalStateException.class, probe::initialize).getMessage());
        } finally {
            server.stop(0);
        }
    }
}
