package io.auralis.publication;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "AURALIS_TEST_POSTGRES_URL", matches = ".+")
class ProductApiIT extends ProductApiFixture {
    @Test
    void completedCardsAndReviewsSurviveJavaRestartWithoutDispatch() throws Exception {
        var b = browser();
        assertEquals(202, b.submit(userInput("恢复原值"), "s".repeat(160)).status());
        var ready =
                b.awaitState(
                        s ->
                                java.util.stream.StreamSupport.stream(
                                                s.path("slots").spliterator(), false)
                                        .allMatch(
                                                slot ->
                                                        slot.path("card")
                                                                .path("long_review_state")
                                                                .asText()
                                                                .equals("READY")));
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!app.getBean(io.auralis.discovery.api.DispatchQueue.class).active().isEmpty()
                && System.nanoTime() < until) Thread.sleep(25);
        int sends = peer.sends.get();
        bootProduct();
        b.connect();
        assertEquals(ready.path("slots"), b.state.path("slots"));
        assertEquals(ready.path("editable_input"), b.state.path("editable_input"));
        assertEquals(ready.path("expires_at"), b.state.path("expires_at"));
        assertEquals(sends, peer.sends.get());
        String candidate = b.state.path("slots").get(0).path("card").path("candidate_id").asText();
        assertEquals(
                "READY",
                b.request(
                                "GET",
                                "/v1/discovery-sessions/"
                                        + b.session()
                                        + "/long-reviews/"
                                        + candidate,
                                null,
                                null)
                        .body()
                        .path("status")
                        .asText());
    }

    @Test
    void foreignSessionAndStaleRevisionCannotModifyCards() throws Exception {
        var owner = browser();
        owner.submit(userInput(""), "owner");
        owner.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        var foreign = browser();
        String path = "/v1/discovery-sessions/" + owner.session();
        assertEquals(400, foreign.request("GET", path, null, null).status());
        var body =
                JSON.createObjectNode()
                        .put("expected_revision", owner.state.path("revision").asLong())
                        .put("action", "HEARD");
        assertEquals(
                400,
                foreign.request("POST", path + "/cards/slot-0001/actions", body, "foreign")
                        .status());
        body.put("expected_revision", 0);
        assertEquals(
                409,
                owner.request("POST", path + "/cards/slot-0001/actions", body, "stale").status());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM discovery.heard_album WHERE subject_id=(SELECT subject_id FROM discovery.session WHERE id=?)",
                        Integer.class,
                        owner.session()));
    }

    @Test
    void submitRestoreReuseAndExplicitRegenerateUseRealHttp() throws Exception {
        var b = browser();
        var input = userInput("保留原文用于编辑预填");
        var response = b.submit(input, "initial");
        assertEquals(202, response.status(), response.body().toString());
        var ready =
                b.awaitState(
                        s ->
                                s.path("status").asText().equals("SUCCEEDED")
                                        && java.util.stream.StreamSupport.stream(
                                                        s.path("slots").spliterator(), false)
                                                .allMatch(
                                                        slot ->
                                                                slot.path("card")
                                                                        .path("long_review_state")
                                                                        .asText()
                                                                        .equals("READY")));
        assertEquals(input, ready.path("editable_input"));
        assertEquals(2, ready.path("recommendation_target_count").asInt());
        String expires = ready.path("expires_at").asText();
        b.refresh();
        assertEquals(expires, b.state.path("expires_at").asText());
        int sends = peer.sends.get();
        assertEquals(200, b.submit(input, "reuse").status());
        assertEquals(sends, peer.sends.get());
        assertTrue(
                Instant.parse(b.state.path("expires_at").asText()).isAfter(Instant.parse(expires)));
        String candidate = b.state.path("slots").get(0).path("card").path("candidate_id").asText();
        int calls = peer.modelCalls.get();
        assertEquals(200, b.action("long-reviews/" + candidate + "/open", null, "open").status());
        b.refresh();
        assertEquals(calls, peer.modelCalls.get());
        assertEquals(
                200,
                b.action("long-reviews/" + candidate + "/regenerate", null, "regenerate").status());
        b.awaitState(
                s ->
                        peer.modelCalls.get() > calls
                                && s.path("slots")
                                        .get(0)
                                        .path("card")
                                        .path("long_review_state")
                                        .asText()
                                        .equals("READY"));
        assertTrue(peer.failures.isEmpty(), peer.failures.toString());
    }

    @Test
    void replacementSupplyThenCacheAndNextRoundKeepHistoryAndNoRawReplay() throws Exception {
        peer.holdReports = true;
        var b = browser();
        b.submit(userInput("原文只发送首轮"), "start");
        b.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        String firstRound = b.state.path("round_id").asText();
        String first = b.state.path("slots").get(0).path("card").path("candidate_id").asText();
        assertEquals(202, b.action("cards/slot-0001/actions", "HEARD", "heard").status());
        b.awaitState(
                s ->
                        s.path("slots").get(0).path("status").asText().equals("READY")
                                && !s.path("slots")
                                        .get(0)
                                        .path("card")
                                        .path("candidate_id")
                                        .asText()
                                        .equals(first));
        b.awaitState(
                s ->
                        s.path("slots")
                                .get(0)
                                .path("card")
                                .path("long_review_state")
                                .asText()
                                .equals("READY"));
        int sends = peer.sends.get();
        assertEquals(200, b.action("cards/slot-0002/actions", "REPLACE", "cached").status());
        assertEquals(sends, peer.sends.get());
        b.refresh();
        peer.hold = true;
        var advanced = b.action("next-batch", null, "next-round");
        assertEquals(202, advanced.status(), advanced.body().toString());
        peer.holdReports = false;
        long drained = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (peer.jobs.values().stream()
                        .anyMatch(
                                j ->
                                        j.start.path("round_id").asText().equals(firstRound)
                                                && !j.released)
                && System.nanoTime() < drained) Thread.sleep(25);
        assertTrue(
                java.util.stream.StreamSupport.stream(
                                b.refresh().path("slots").spliterator(), false)
                        .allMatch(slot -> slot.path("status").asText().equals("SUPPLYING")));
        peer.hold = false;
        var next =
                b.awaitState(
                        s ->
                                !s.path("round_id").asText().equals(firstRound)
                                        && s.path("status").asText().equals("SUCCEEDED"));
        assertTrue(
                peer.jobs.values().stream()
                        .filter(j -> j.start.path("round_seq").asInt() > 1)
                        .allMatch(j -> j.start.path("input").path("raw").isNull()));
        assertTrue(
                peer.jobs.values().stream()
                        .anyMatch(
                                j ->
                                        j.start.path("round_seq").asInt() > 1
                                                && j.start
                                                        .path("input")
                                                        .path("exclusions")
                                                        .toString()
                                                        .contains(first)));
        assertEquals(
                1,
                tx.write(
                                () ->
                                        sessions.heardAlbums(
                                                sessions.current(
                                                                subjects.authenticate(b.token())
                                                                        .orElseThrow()
                                                                        .subjectId())
                                                        .orElseThrow()
                                                        .scope()))
                        .size());
        assertTrue(peer.failures.isEmpty(), peer.failures.toString());
    }

    @Test
    void basicRescreenUsesUnshownResultsAndNeverRunsAutomatically() throws Exception {
        peer.basicNext.set(true);
        var b = browser();
        b.submit(userInput(""), "basic");
        b.awaitState(s -> s.path("analysis_mode").asText().equals("BASIC"));
        var old = new HashSet<String>();
        b.state.path("slots").forEach(s -> old.add(s.path("card").path("candidate_id").asText()));
        int sends = peer.sends.get();
        b.refresh();
        assertEquals(sends, peer.sends.get());
        assertEquals(202, b.action("rescreen", null, "rescreen").status());
        var next =
                b.awaitState(
                        s ->
                                s.path("analysis_mode").asText().equals("PERSONALIZED")
                                        && s.path("status").asText().equals("SUCCEEDED"));
        for (var slot : next.path("slots"))
            assertFalse(old.contains(slot.path("card").path("candidate_id").asText()));
        assertTrue(peer.failures.isEmpty(), peer.failures.toString());
    }

    @Test
    void queueFullRollsBackInputAndSameKeyCanBeRetried() throws Exception {
        peer.hold = true;
        for (int n = 0; n < 3; n++) {
            var b = browser();
            assertEquals(202, b.submit(userInput(""), "running-" + n).status());
            b.awaitState(s -> s.path("status").asText().equals("RUNNING"));
        }
        for (int n = 0; n < 5; n++) {
            var b = browser();
            assertEquals(202, b.submit(userInput(""), "queued-" + n).status());
        }
        var refused = browser();
        var response = refused.submit(userInput(""), "retry");
        assertEquals(429, response.status());
        assertEquals("QUEUE_FULL", response.body().path("code").asText());
        assertFalse(response.body().path("idempotency_consumed").asBoolean());
        assertTrue(refused.refresh().path("session_id").isNull());
        peer.hold = false;
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        Reply retried;
        do {
            Thread.sleep(100);
            retried = refused.submit(userInput(""), "retry");
        } while (retried.status() == 429 && System.nanoTime() < until);
        assertTrue(Set.of(200, 202).contains(retried.status()), retried.body().toString());
    }

    @Test
    void noPositiveUsesInjectedFixedListAndModeDoesNotChooseDifferentAlbums() throws Exception {
        var b = browser();
        var input = userInput("");
        input.putArray("liked_genres");
        input.putArray("disliked_genre_ids").add(ROCK);
        b.submit(input, "curated");
        b.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        assertEquals("CURATED", b.state.path("analysis_mode").asText());
        assertTrue(
                peer.jobs.values().stream()
                        .anyMatch(
                                j -> j.start.path("operation").asText().equals("CURATED_REVIEWS")));
        assertTrue(peer.failures.isEmpty(), peer.failures.toString());
    }

    @Test
    void editingCreatesNewSessionAndDuplicateSubmissionDoesNotDispatchAgain() throws Exception {
        var b = browser();
        b.submit(userInput(""), "one");
        b.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        String old = b.session();
        var input = userInput("新的文字");
        var body =
                JSON.createObjectNode()
                        .put("expected_session_id", old)
                        .put("expected_revision", b.state.path("revision").asLong());
        body.set("input", input);
        var first = b.request("POST", "/v1/discovery-sessions", body, "edit");
        assertEquals(202, first.status());
        String current = b.session();
        assertNotEquals(old, current);
        var replay = b.request("POST", "/v1/discovery-sessions", body, "edit");
        assertEquals(current, replay.body().path("session_id").asText());
        assertEquals(400, b.request("GET", "/v1/discovery-sessions/" + old, null, null).status());
    }
}
