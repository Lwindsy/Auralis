package io.auralisy.discovery;

import static io.auralisy.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralisy.bootstrap.*;
import io.auralisy.discovery.api.PythonClient;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PythonClientIT {
    static final String TOKEN = "be010-real-python-loopback-token-12345";

    static final class ProcessPeer implements AutoCloseable {
        final Process process;
        final PythonHttpClient client;

        ProcessPeer(String javaBoot) throws Exception {
            String python = System.getenv("AURALIS_RECALL_PYTHON");
            assertNotNull(python);
            var intelligence =
                    Path.of(System.getProperty("basedir")).resolveSibling("intelligence");
            String script =
                    """
                import asyncio,sys
                from aiohttp import web
                from auralisy_intelligence.agents import RoundSupervisor
                from auralisy_intelligence.agents.control_double import JavaControlDouble
                from auralisy_intelligence.agents.http import create_execution_app
                async def stage(execution):
                    await asyncio.Event().wait()
                async def main():
                    control=JavaControlDouble(sys.argv[1])
                    app=create_execution_app(RoundSupervisor(),control,lambda _:stage,token=sys.argv[2],prepare=control.register)
                    runner=web.AppRunner(app);await runner.setup()
                    site=web.TCPSite(runner,'127.0.0.1',0);await site.start()
                    print(site._server.sockets[0].getsockname()[1],flush=True)
                    await asyncio.Event().wait()
                asyncio.run(main())
                """;
            var command = new ProcessBuilder(python, "-u", "-c", script, javaBoot, TOKEN);
            command.environment().put("PYTHONPATH", intelligence.resolve("src").toString());
            command.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = command.start();
            String port;
            try (var worker = Executors.newSingleThreadExecutor()) {
                port =
                        worker.submit(
                                        () ->
                                                new BufferedReader(
                                                                new InputStreamReader(
                                                                        process.getInputStream()))
                                                        .readLine())
                                .get(10, TimeUnit.SECONDS);
            }
            var properties = new OnlineProperties();
            properties.setPythonUrl("http://127.0.0.1:" + Integer.parseInt(port));
            properties.setServiceToken(TOKEN);
            client = new PythonHttpClient(properties, new WireProtocol(JSON));
        }

        public void close() throws Exception {
            client.close();
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    static ObjectNode start(String javaBoot, String pythonBoot) throws Exception {
        var p =
                (ObjectNode)
                        JSON.readTree(
                                        Path.of(System.getProperty("basedir"))
                                                .resolveSibling(
                                                        "intelligence/tests/fixtures/agent-contract-cases.json")
                                                .toFile())
                                .get(0)
                                .path("message")
                                .path("payload")
                                .deepCopy();
        p.put("execution_id", UUID.randomUUID().toString())
                .put("erasure_handle", UUID.randomUUID().toString());
        p.putObject("boot").put("coordinator_boot_id", javaBoot).put("python_boot_id", pythonBoot);
        ((ObjectNode) p.path("deadlines"))
                .put(
                        "round_deadline_at",
                        Instant.now()
                                .plusSeconds(120)
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toString());
        p.put("payload_digest", contentDigest(p, "payload_digest"));
        return p;
    }

    @Test
    void actualPythonCapacityDedupCancellationAndBootEvidence() throws Exception {
        String originalBoot;
        try (var peer = new ProcessPeer("java-http")) {
            var runtime = JSON.readTree(peer.client.runtime());
            originalBoot = runtime.path("boot_id").asText();
            assertEquals(3, runtime.path("execution_capacity").asInt());
            var plans = new ArrayList<ObjectNode>();
            for (int n = 0; n < 3; n++) {
                var p = start("java-http", originalBoot);
                plans.add(p);
                peer.client.start(p.path("execution_id").asText(), p.toString());
            }
            var first = plans.getFirst();
            String id = first.path("execution_id").asText();
            peer.client.start(id, first.toString());
            assertEquals(3, JSON.readTree(peer.client.runtime()).path("active").size());
            var fourth = start("java-http", originalBoot);
            assertThrows(
                    PythonClient.Unavailable.class,
                    () ->
                            peer.client.start(
                                    fourth.path("execution_id").asText(), fourth.toString()));
            var absent =
                    JSON.readTree(
                            peer.client.admission(
                                    fourth.path("execution_id").asText(),
                                    originalBoot,
                                    fourth.path("payload_digest").asText(),
                                    fourth.path("deadlines").path("round_deadline_at").asText()));
            assertEquals("NOT_ACCEPTED", absent.path("status").asText());
            assertTrue(
                    JSON.readTree(
                                    peer.client.cancel(
                                            id, "java-http", originalBoot, "USER_CANCELLED"))
                            .path("slot_released")
                            .asBoolean());
            assertEquals(2, JSON.readTree(peer.client.runtime()).path("active").size());
            assertEquals(
                    "TERMINAL",
                    JSON.readTree(
                                    peer.client.admission(
                                            id,
                                            originalBoot,
                                            first.path("payload_digest").asText(),
                                            first.path("deadlines")
                                                    .path("round_deadline_at")
                                                    .asText()))
                            .path("status")
                            .asText());
            peer.client.start(fourth.path("execution_id").asText(), fourth.toString());
            assertEquals(3, JSON.readTree(peer.client.runtime()).path("active").size());
        }
        try (var restarted = new ProcessPeer("java-http")) {
            assertNotEquals(
                    originalBoot,
                    JSON.readTree(restarted.client.runtime()).path("boot_id").asText());
        }
    }

    @Test
    void malformedOrUnauthenticatedResponsesNeverBecomeAdmission() throws Exception {
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/runtime",
                e -> {
                    byte[] body = "{\"status\":\"READY\"}".getBytes();
                    e.sendResponseHeaders(200, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        server.start();
        var props = new OnlineProperties();
        props.setPythonUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setServiceToken(TOKEN);
        try (var peer = new PythonHttpClient(props, new WireProtocol(JSON))) {
            assertThrows(PythonClient.Unavailable.class, peer::runtime);
        } finally {
            server.stop(0);
        }
    }
}
