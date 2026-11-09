package io.auralis.publication;

import static io.auralis.contracts.CanonicalJson.JSON;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.UUID;

/**
 * Real product HTTP and disposable DB with synthetic execution; never shipped in the online jar.
 */
public final class ProductBrowserHarness extends ProductApiFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output JSON path");
        System.setProperty("basedir", Path.of("apps/backend").toAbsolutePath().toString());
        System.setProperty("auralis.test.product-origin", "http://127.0.0.1:4189");
        var harness = new ProductBrowserHarness();
        var output = Path.of(args[0]).toAbsolutePath();
        var stop = output.resolveSibling(output.getFileName() + ".stop");
        if (Files.exists(output) || Files.exists(stop))
            throw new IllegalArgumentException("Output already exists");
        HttpServer control = null;
        try {
            harness.setup();
            harness.productSetup();
            String key = UUID.randomUUID().toString();
            control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            control.createContext(
                    "/control",
                    exchange -> {
                        try {
                            if (!key.equals(exchange.getRequestHeaders().getFirst("X-Test-Key"))) {
                                exchange.sendResponseHeaders(403, -1);
                                return;
                            }
                            var request = JSON.readTree(exchange.getRequestBody().readNBytes(4096));
                            if (request.has("hold"))
                                harness.peer.hold = request.path("hold").asBoolean();
                            if (request.has("recall_limit"))
                                harness.peer.recallLimit = request.path("recall_limit").asInt();
                            if (request.has("basic"))
                                harness.peer.basicNext.set(request.path("basic").asBoolean());
                            if (request.has("fail_long"))
                                harness.peer.failLongNext.set(
                                        request.path("fail_long").asBoolean());
                            if (request.has("long_delay_ms"))
                                harness.peer.longDelayMs = request.path("long_delay_ms").asLong();
                            String session = request.path("session_id").asText();
                            if (request.path("expire").asBoolean()) {
                                harness.admin.update(
                                        "UPDATE discovery.subject SET revision=revision+1,last_user_action=clock_timestamp()-interval '31 days',expires_at=clock_timestamp()-interval '1 second' WHERE id=(SELECT subject_id FROM discovery.session WHERE id=?)",
                                        session);
                                harness.admin.update(
                                        "UPDATE discovery.session SET revision=revision+1,last_user_action=clock_timestamp()-interval '31 days',expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                                        session);
                            }
                            var result =
                                    JSON.createObjectNode()
                                            .put(
                                                    "active",
                                                    harness.app
                                                                    .getBean(
                                                                            io.auralis.discovery.api
                                                                                    .DispatchQueue
                                                                                    .class)
                                                                    .active()
                                                                    .size()
                                                            + harness.admin.queryForObject(
                                                                    "SELECT count(*) FROM discovery.discovery_intent WHERE status='WAITING'",
                                                                    Integer.class))
                                            .put("sends", harness.peer.sends.get())
                                            .put("model_calls", harness.peer.modelCalls.get())
                                            .put(
                                                    "heard",
                                                    harness.admin.queryForObject(
                                                            "SELECT count(*) FROM discovery.heard_album WHERE subject_id=(SELECT subject_id FROM discovery.session WHERE id=?)",
                                                            Integer.class,
                                                            session));
                            byte[] body = JSON.writeValueAsBytes(result);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                        } catch (Exception failure) {
                            exchange.sendResponseHeaders(500, -1);
                        } finally {
                            exchange.close();
                        }
                    });
            control.start();
            Files.createDirectories(output.getParent());
            Files.writeString(
                    output,
                    JSON.createObjectNode()
                            .put("origin", harness.base)
                            .put(
                                    "control",
                                    "http://127.0.0.1:"
                                            + control.getAddress().getPort()
                                            + "/control")
                            .put("key", key)
                            .toString());
            while (!Files.exists(stop)) Thread.sleep(100);
        } finally {
            if (control != null) control.stop(0);
            if (harness.peer != null) harness.cleanProduct();
            harness.closeProduct();
            harness.close();
            Files.deleteIfExists(output);
            Files.deleteIfExists(stop);
        }
    }
}
