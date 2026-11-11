package io.auralis.publication;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auralis.budget.api.*;
import io.auralis.catalog.api.*;
import io.auralis.discovery.api.*;
import io.auralis.evidence.api.*;
import io.auralis.persistence.PersistenceRejected;
import io.auralis.preference.api.*;
import io.auralis.publication.api.*;
import io.auralis.session.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "AURALIS_TEST_POSTGRES_URL", matches = ".+")
class PublicationDisplayIT extends PublicationFixture {
    @Test
    void endedResearchIsRetryableAndTechnicalFailureIsNotMaterialShortage() throws Exception {
        var f = flow(1);
        publish(f, 1);
        String round = f.context().execution().evidence().round();
        jdbc.update(
                "UPDATE discovery.long_review SET state='NEEDS_MORE_RESEARCH',reason='REVIEW_MATERIAL_INSUFFICIENT',revision=revision+1 WHERE round_id=?",
                round);
        assertEquals(
                "NEEDS_MORE_RESEARCH",
                JSON.readTree(publication.longReview(latest(f), round, album(1)))
                        .path("status")
                        .asText());
        release();
        var shortage = JSON.readTree(publication.longReview(latest(f), round, album(1)));
        assertEquals("FAILED", shortage.path("status").asText());
        assertEquals("REVIEW_MATERIAL_INSUFFICIENT", shortage.path("message_key").asText());
        jdbc.update(
                "UPDATE discovery.round_candidate SET screening=jsonb_set(screening,'{screen,status}','\"FAILED\"'::jsonb) WHERE round_id=? AND candidate_id=?",
                round,
                album(1));
        var failure = JSON.readTree(publication.longReview(latest(f), round, album(1)));
        assertEquals("FAILED", failure.path("status").asText());
        assertEquals("RESEARCH_FAILED", failure.path("message_key").asText());
    }

    @Test
    void completedPhysicalWorkCannotLeaveSupplySpinnerRunning() throws Exception {
        var f = flow(1);
        publish(f, 1);
        String round = f.context().execution().evidence().round();
        jdbc.update(
                "UPDATE discovery.publication_round SET supply_state='SUPPLYING' WHERE round_id=?",
                round);
        assertEquals(
                "SUPPLYING",
                JSON.readTree(publication.state(latest(f), round)).path("supply_state").asText());
        release();
        var state = JSON.readTree(publication.state(latest(f), round));
        assertEquals("PAUSED", state.path("supply_state").asText());
        assertEquals("PARTIAL", state.path("status").asText());
        assertFalse(state.path("slots").isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {10, 100, 101, 500})
    void publicationUsesTheAuthorizedThresholdIncludingItsBoundary(int minimum) throws Exception {
        if (minimum > 100) {
            assertEquals(
                    "ADMISSION_UNVERIFIED",
                    assertThrows(PersistenceRejected.class, () -> flow(1, minimum)).getMessage());
            return;
        }
        var f = flow(1, minimum);
        var result =
                JSON.readTree(
                        publication.deliver(
                                f.context(),
                                delivery(f, "CARD_BATCH", batch(f, 1, "PERSONALIZED"), "threshold")
                                        .toString()));
        assertEquals(1, result.path("published_candidate_ids").size());
    }

    @Test
    void actualPythonClientConsumesTransactionalDeliveryReceipt() throws Exception {
        var f = flow(2);
        var payload = delivery(f, "CARD_BATCH", batch(f, 3, "PERSONALIZED"), "python");
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/",
                exchange -> {
                    try {
                        if (!"Bearer be011-loopback"
                                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            exchange.sendResponseHeaders(401, -1);
                            return;
                        }
                        var message = JSON.readTree(exchange.getRequestBody().readAllBytes());
                        String value =
                                publication.deliver(
                                        f.context(), message.path("payload").toString());
                        var response =
                                JSON.createObjectNode()
                                        .put("schema_version", "intel.v1")
                                        .put("contract_revision", "0.32")
                                        .put("request_id", message.path("request_id").asText())
                                        .put("message_type", "DeliveryResult");
                        response.putObject("trace_context")
                                .put("trace_id", "test")
                                .put("span_id", "test");
                        response.set("payload", JSON.readTree(value));
                        byte[] bytes = JSON.writeValueAsBytes(response);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception e) {
                        exchange.sendResponseHeaders(500, -1);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        String script =
                """
            import asyncio,json,sys
            from auralis_intelligence.agents.http import ControlHTTPClient
            from auralis_intelligence.contracts import validate_definition
            async def run():
                p=json.load(sys.stdin);validate_definition('Delivery',p)
                client=ControlHTTPClient(sys.argv[1],'be011-loopback')
                try:
                    result=await client.request(sys.argv[2],'delivery',p)
                    assert len(result['published_candidate_ids'])==2,result
                    assert result==await client.request(sys.argv[2],'delivery',p)
                finally:await client.close()
            asyncio.run(run())
            """;
        try {
            var command =
                    new ProcessBuilder(
                            System.getenv("AURALIS_RECALL_PYTHON"),
                            "-X",
                            "utf8",
                            "-c",
                            script,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            f.context().execution().evidence().execution());
            command.environment()
                    .put(
                            "PYTHONPATH",
                            java.nio.file.Path.of(System.getProperty("basedir"))
                                    .resolveSibling("intelligence/src")
                                    .toString());
            command.redirectError(ProcessBuilder.Redirect.INHERIT);
            command.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            var child = command.start();
            try (var input = child.getOutputStream()) {
                input.write(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void backgroundCardsWaitForAnExplicitDisplayIntent() throws Exception {
        var f = flow(1);
        var d = delivery(f, "CARD_BATCH", batch(f, 3, "PERSONALIZED"), "background");
        d.putNull("run_id");
        var result = JSON.readTree(publication.deliver(f.context(), d.toString()));
        assertTrue(result.path("published_candidate_ids").isEmpty());
        assertEquals(3, result.path("display_state").path("ready_unshown_ids").size());
        assertEquals(
                "EMPTY",
                JSON.readTree(
                                publication.state(
                                        latest(f), f.context().execution().evidence().round()))
                        .path("slots")
                        .get(0)
                        .path("status")
                        .asText());
    }

    @Test
    void zeroResultsAndUnverifiedUsageAreNotSuccessfulCards() throws Exception {
        var f = flow(2);
        var empty = batch(f, 0, "PERSONALIZED");
        empty.put("reason_code", "NO_MATCH");
        publication.deliver(f.context(), delivery(f, "CARD_BATCH", empty, "empty").toString());
        assertEquals(
                "NO_MATCH",
                JSON.readTree(
                                publication.state(
                                        latest(f), f.context().execution().evidence().round()))
                        .path("status")
                        .asText());
        var other = flow(1);
        var p = batch(other, 1, "PERSONALIZED");
        ((ObjectNode) p.path("cards").get(0).path("short_review"))
                .put("invocation_id", other.invocation() + "-unverified");
        var rejected =
                JSON.readTree(
                        publication.deliver(
                                other.context(),
                                delivery(other, "CARD_BATCH", p, "unverified").toString()));
        assertEquals("REJECTED", rejected.path("status").asText());
        assertTrue(rejected.path("published_candidate_ids").isEmpty());
    }

    @Test
    void atomicReceiptReplayAndPartialAdmission() throws Exception {
        var f = flow(2);
        var p = batch(f, 3, "PERSONALIZED");
        ((ObjectNode) p.path("cards").get(1).path("short_review").path("citations").get(0))
                .put("read_id", UUID.randomUUID().toString());
        var d = delivery(f, "CARD_BATCH", p, "mixed");
        String result = publication.deliver(f.context(), d.toString());
        assertEquals("PARTIALLY_ACCEPTED", JSON.readTree(result).path("status").asText());
        assertEquals(2, JSON.readTree(result).path("published_candidate_ids").size());
        assertEquals(
                JSON.readTree(result),
                JSON.readTree(publication.deliver(f.context(), d.toString())));
        var old = d.deepCopy();
        ((ObjectNode) old.path("payload")).put("reason_code", "changed");
        old.put("payload_digest", digest(old.path("payload")));
        assertThrows(Exception.class, () -> publication.deliver(f.context(), old.toString()));
    }

    @Test
    void replacementsConsumeDistinctCandidatesAndOnlyHeardWritesHistory() throws Exception {
        var f = flow(2);
        publish(f, 4);
        var s = latest(f);
        publication.operate(
                s,
                f.context().execution().evidence().round(),
                "replace",
                "REPLACE",
                "slot-0001",
                (scope, round, slots) -> {
                    throw new AssertionError();
                });
        assertTrue(tx.write(() -> sessions.heardAlbums(latest(f))).isEmpty());
        publication.operate(
                latest(f),
                f.context().execution().evidence().round(),
                "heard",
                "HEARD",
                "slot-0002",
                (scope, round, slots) -> {
                    throw new AssertionError();
                });
        assertEquals(
                List.of(UUID.fromString(album(2))),
                tx.write(() -> sessions.heardAlbums(latest(f))));
        var state =
                JSON.readTree(
                        publication.state(latest(f), f.context().execution().evidence().round()));
        assertNotEquals(
                state.path("slots").get(0).path("card").path("candidate_id"),
                state.path("slots").get(1).path("card").path("candidate_id"));
    }

    @Test
    void queueRejectionRollsBackSlotHeardAndRevision() throws Exception {
        var f = flow(1);
        publish(f, 1);
        var s = latest(f);
        String before = publication.state(s, f.context().execution().evidence().round());
        assertThrows(
                PersistenceRejected.class,
                () ->
                        publication.operate(
                                s,
                                f.context().execution().evidence().round(),
                                "queue",
                                "HEARD",
                                "slot-0001",
                                (scope, r, slots) -> {
                                    throw new PersistenceRejected("QUEUE_FULL");
                                }));
        assertEquals(
                JSON.readTree(before),
                JSON.readTree(publication.state(s, f.context().execution().evidence().round())));
        assertTrue(tx.write(() -> sessions.heardAlbums(s)).isEmpty());
    }

    @Test
    void concurrentReplacementHasOneRevisionWinner() throws Exception {
        var f = flow(1);
        publish(f, 4);
        var scope = latest(f);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Future<Boolean>>();
            for (int n = 0; n < 2; n++) {
                String command = "race-" + n;
                calls.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        publication.operate(
                                                scope,
                                                f.context().execution().evidence().round(),
                                                command,
                                                "REPLACE",
                                                "slot-0001",
                                                (s, r, slots) -> null);
                                        return true;
                                    } catch (PersistenceRejected e) {
                                        return false;
                                    }
                                }));
            }
            gate.countDown();
            int winners = 0;
            for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
    }

    @Test
    void longReviewIsIndependentAndOldGenerationCannotOverwrite() throws Exception {
        var f = flow(1);
        String longCall = f.invocation() + "-long";
        var grant = JSON.createObjectNode();
        var call =
                grant.putArray("items")
                        .addObject()
                        .put("invocation_id", longCall)
                        .put("attempt", 1)
                        .put("purpose", "WRITE_LONG")
                        .put("cost_class", "MODEL")
                        .put("lane", "BACKGROUND")
                        .putNull("run_id")
                        .put("candidate_id", album(1))
                        .put("model_binding_ref", "test-model")
                        .put("max_input_tokens", 100)
                        .put("max_output_tokens", 100)
                        .put("input_digest", "sha256:" + "9".repeat(64));
        call.putArray("read_ids");
        budget.grants(f.context().execution().budget(), grant.toString());
        budget.settle(
                f.context().execution().evidence().execution(),
                JSON.createObjectNode()
                        .put("invocation_id", longCall)
                        .put("receipt_id", longCall + ":usage")
                        .put("outcome", "COMPLETED")
                        .put("finish", "COMPLETE")
                        .put("input_tokens", 5)
                        .put("cached_input_tokens", 0)
                        .put("output_tokens", 5)
                        .putNull("provider_request_ref")
                        .toString());

        publish(f, 2);
        var payload =
                JSON.createObjectNode()
                        .put("candidate_id", album(1))
                        .put("status", "READY")
                        .put("han_char_count", 2)
                        .put("prompt_version", "test")
                        .putNull("reason_code");
        payload.putObject("sections")
                .put("production", "制作")
                .put("instrumentation", "")
                .put("vocals", "")
                .put("mood", "");
        payload.putArray("citations")
                .addObject()
                .put("marker", "R1")
                .put("read_id", f.reads().get(album(1)));
        payload.putArray("extra_read_ids");
        payload.putArray("missing_sections");
        payload.putArray("invocation_ids").add(longCall);
        publication.deliver(f.context(), delivery(f, "LONG_REVIEW", payload, "long").toString());
        assertEquals(
                "READY",
                JSON.readTree(
                                publication.longReview(
                                        latest(f),
                                        f.context().execution().evidence().round(),
                                        album(1)))
                        .path("status")
                        .asText());
        String nextRun = UUID.randomUUID().toString();
        publication.regenerate(
                latest(f),
                f.context().execution().evidence().round(),
                album(1),
                (s, r, slots) -> {
                    discovery.createIntent(
                            s,
                            r,
                            nextRun,
                            "regenerate",
                            sessions.inputRevision(s),
                            "sha256:" + "8".repeat(64),
                            Instant.now(),
                            Instant.now().plusSeconds(30));
                    return nextRun;
                });
        assertThrows(
                PersistenceRejected.class,
                () ->
                        publication.deliver(
                                f.context(),
                                delivery(f, "LONG_REVIEW", payload, "late-long").toString()));
    }

    @Test
    void basicPartialNoMatchAndCancellationRemainDistinct() throws Exception {
        var f = flow(3);
        publication.deliver(
                f.context(), delivery(f, "CARD_BATCH", batch(f, 1, "BASIC"), "basic").toString());
        var state =
                JSON.readTree(
                        publication.state(latest(f), f.context().execution().evidence().round()));
        assertEquals("BASIC", state.path("analysis_mode").asText());
        assertEquals("PARTIAL", state.path("status").asText());
        tx.write(
                () -> {
                    discovery.lockRuntime();
                    app.getBean(SessionExecutions.class)
                            .invalidate(List.of(latest(f).sessionId()), "CANCELLED");
                    return null;
                });
        var late = JSON.createObjectNode().put("state", "PAUSED").put("reason_code", "cancelled");
        late.putObject("counts")
                .put("unresearched", 0)
                .put("reserve", 0)
                .put("in_flight", 0)
                .put("ready_unshown_known", 0);
        var rejected =
                assertThrows(
                        PersistenceRejected.class,
                        () ->
                                publication.deliver(
                                        f.context(),
                                        delivery(f, "SUPPLY_STATUS", late, "late").toString()));
        assertEquals("EXECUTION_GONE", rejected.getMessage());
    }
}
