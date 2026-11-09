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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"HEARD", "REPLACE"})
    void undoRestoresLastCardAndFeedbackAndSurvivesLateSupply(String action) throws Exception {
        var b = browser();
        b.submit(userInput(""), "undo-initial");
        b.awaitState(state -> state.path("status").asText().equals("SUCCEEDED"));
        for (var job : peer.jobs.values()) job.done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        b.refresh();
        String original = b.state.path("slots").get(0).path("card").path("candidate_id").asText();
        peer.hold = true;
        try {
            var changed = b.action("cards/slot-0001/actions", action, "undo-change");
            assertTrue(Set.of(200, 202).contains(changed.status()), changed.body().toString());
            b.refresh();
            var body =
                    JSON.createObjectNode()
                            .put("expected_revision", b.state.path("revision").asLong());
            String endpoint = "/v1/discovery-sessions/" + b.session() + "/undo-last-card";
            var undone = b.request("POST", endpoint, body, "undo-once");
            assertEquals(200, undone.status(), undone.body().toString());
            assertEquals(
                    original,
                    b.state.path("slots").get(0).path("card").path("candidate_id").asText());
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM discovery.heard_album WHERE subject_id=(SELECT subject_id FROM discovery.session WHERE id=?)",
                            Integer.class,
                            b.session()));
            var replay = b.request("POST", endpoint, body, "undo-once");
            assertEquals(undone.body(), replay.body());
            assertEquals(400, b.action("undo-last-card", null, "undo-again").status());
        } finally {
            peer.hold = false;
        }
        for (var job : peer.jobs.values()) job.done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        b.refresh();
        assertEquals(
                original, b.state.path("slots").get(0).path("card").path("candidate_id").asText());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 5})
    void exhaustedContinuationContinuesThePendingBatchIntoANewRound(int sequence) throws Exception {
        var b = browser();
        b.submit(userInput(""), "drained-initial");
        b.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        String originalRound = b.state.path("round_id").asText();
        for (var job : peer.jobs.values()) job.done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        jdbc.update(
                "UPDATE discovery.publication_round SET supply_state='SUFFICIENT' WHERE round_id=?",
                originalRound);
        jdbc.update(
                "UPDATE discovery.recommendation_round SET round_seq=? WHERE id=?",
                sequence,
                originalRound);
        peer.exhaustSupplyNext.set(true);
        b.refresh();
        var response = b.action("next-batch", null, "drained-batch");
        assertEquals(202, response.status(), response.body().toString());
        if (sequence == 5) {
            b.awaitState(s -> s.path("supply_state").asText().equals("FINAL_EXHAUSTED"));
            assertEquals("NO_MATCH", b.state.path("status").asText());
            assertEquals(originalRound, b.state.path("round_id").asText());
            assertTrue(
                    java.util.stream.StreamSupport.stream(
                                    b.state.path("slots").spliterator(), false)
                            .allMatch(slot -> slot.path("status").asText().equals("EMPTY")));
            assertTrue(peer.failures.isEmpty(), peer.failures.toString());
            return;
        }
        b.awaitState(
                s ->
                        !s.path("round_id").asText().equals(originalRound)
                                && s.path("status").asText().equals("SUCCEEDED"));
        assertEquals(
                2,
                java.util.stream.StreamSupport.stream(b.state.path("slots").spliterator(), false)
                        .filter(slot -> slot.path("status").asText().equals("READY"))
                        .count());
        assertTrue(peer.failures.isEmpty(), peer.failures.toString());
    }

    @Test
    void explicitThresholdAlsoFiltersTheOtherwiseExemptCuratedPool() throws Exception {
        var b = browser();
        var input = userInput("").put("audience_min_listeners", 500);
        input.putArray("liked_genres");
        input.putArray("disliked_genre_ids").add(ROCK);
        assertTrue(Set.of(200, 202).contains(b.submit(input, "curated-500").status()));
        b.awaitState(s -> s.path("status").asText().equals("NO_MATCH"));
        input.put("audience_min_listeners", 75);
        assertTrue(Set.of(200, 202).contains(b.submit(input, "curated-75").status()));
        b.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        assertEquals("CURATED", b.state.path("analysis_mode").asText());
    }

    @Test
    void requestedThresholdSurvivesRestoreAndCanBeChanged() throws Exception {
        var b = browser();
        var input = userInput("").put("audience_min_listeners", 75);
        assertEquals(202, b.submit(input, "threshold-75").status());
        b.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        b.connect();
        assertEquals(75, b.state.path("editable_input").path("audience_min_listeners").asInt());
        input.put("audience_min_listeners", 500);
        assertEquals(202, b.submit(input, "threshold-500").status());
        b.awaitState(s -> s.path("status").asText().equals("NO_MATCH"));
        assertEquals(500, b.state.path("editable_input").path("audience_min_listeners").asInt());
        for (int invalid : new int[] {0, -1}) {
            input.put("audience_min_listeners", invalid);
            assertEquals(400, b.submit(input, "invalid-" + invalid).status());
        }
    }

    @Test
    void changingOnlyConfigurationValueRecomputesDigestAndChangesDefault() throws Exception {
        var changed = settings.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("agent_policy"))
                .put("audience_min_listeners", 500);
        try {
            java.nio.file.Files.writeString(configFile, changed.toPrettyString());
            bootProduct();
            var b = browser();
            assertEquals(202, b.submit(userInput(""), "configured-500").status());
            b.awaitState(s -> s.path("status").asText().equals("NO_MATCH"));
        } finally {
            java.nio.file.Files.writeString(configFile, settings.toPrettyString());
            bootProduct();
        }
    }

    @Test
    void interruptedPhysicalExecutionCanRegenerateLongReviewExplicitly() throws Exception {
        peer.failLongNext.set(true);
        var b = browser();
        b.submit(userInput(""), "interrupted-long");
        b.awaitState(
                s ->
                        s.path("slots")
                                .get(0)
                                .path("card")
                                .path("long_review_state")
                                .asText()
                                .equals("INTERRUPTED"));
        String candidate = b.state.path("slots").get(0).path("card").path("candidate_id").asText();
        int calls = peer.modelCalls.get();
        b.refresh();
        assertEquals(calls, peer.modelCalls.get());
        var result = b.action("long-reviews/" + candidate + "/regenerate", null, "explicit-retry");
        assertEquals(200, result.status(), result.body().toString());
        b.awaitState(
                s ->
                        s.path("slots")
                                .get(0)
                                .path("card")
                                .path("long_review_state")
                                .asText()
                                .equals("READY"));
        assertTrue(peer.modelCalls.get() > calls);
    }

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
        var submitted = b.submit(input, "curated");
        assertTrue(Set.of(200, 202).contains(submitted.status()), submitted.body().toString());
        b.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        assertEquals("CURATED", b.state.path("analysis_mode").asText());
        assertEquals("SUFFICIENT", b.state.path("supply_state").asText());
        assertTrue(
                peer.jobs.values().stream()
                        .noneMatch(j -> j.start.path("session_id").asText().equals(b.session())));
        for (var slot : b.state.path("slots"))
            assertEquals("READY", slot.path("card").path("long_review_state").asText());
        assertTrue(peer.failures.isEmpty(), peer.failures.toString());
    }

    @Test
    void curatedModesKeepOrderAndAllExcludedProducesNoMatch() throws Exception {
        int sends = peer.sends.get();
        for (String mode : List.of("familiar", "explore", "surprise")) {
            var b = browser();
            var input = userInput("");
            input.put("mode", mode);
            input.putArray("liked_genres");
            input.putArray("disliked_genre_ids").add(ROCK);
            assertEquals(200, b.submit(input, "mode-" + mode).status());
            assertEquals(
                    album(1),
                    b.state.path("slots").get(0).path("card").path("candidate_id").asText());
            assertEquals(
                    album(2),
                    b.state.path("slots").get(1).path("card").path("candidate_id").asText());
        }
        var empty = browser();
        var input = userInput("");
        input.putArray("liked_genres");
        input.putArray("disliked_genre_ids").add(G);
        assertEquals(200, empty.submit(input, "all-excluded").status());
        assertEquals("NO_MATCH", empty.state.path("status").asText());
        assertEquals("FINAL_EXHAUSTED", empty.state.path("supply_state").asText());
        assertEquals(sends, peer.sends.get());
    }

    @Test
    void preparedCuratedBatchExhaustsAndRestoresWithoutPython() throws Exception {
        int sends = peer.sends.get();
        var b = browser();
        var input = userInput("");
        input.putArray("liked_genres");
        input.putArray("disliked_genre_ids").add(ROCK);
        assertEquals(200, b.submit(input, "prepared").status());
        assertEquals(
                album(1), b.state.path("slots").get(0).path("card").path("candidate_id").asText());
        assertEquals(200, b.action("cards/slot-0001/actions", "HEARD", "heard").status());
        assertEquals(
                album(3), b.state.path("slots").get(0).path("card").path("candidate_id").asText());
        bootProduct();
        b.connect();
        assertEquals(
                album(3), b.state.path("slots").get(0).path("card").path("candidate_id").asText());
        var batch = b.action("next-batch", null, "batch");
        assertEquals(200, batch.status(), batch.body().toString());
        assertEquals("PARTIAL", b.state.path("status").asText());
        assertEquals(
                album(4), b.state.path("slots").get(0).path("card").path("candidate_id").asText());
        assertEquals(200, b.action("cards/slot-0001/actions", "REPLACE", "last").status());
        assertEquals("NO_MATCH", b.state.path("status").asText());
        assertEquals("FINAL_EXHAUSTED", b.state.path("supply_state").asText());
        assertEquals(sends, peer.sends.get());
    }

    @Test
    void parsedNoPositiveHandsOffToPreparedContentWithoutSecondExecution() throws Exception {
        var b = browser();
        var input = userInput("不要摇滚");
        input.putArray("liked_genres");
        input.putArray("disliked_genre_ids").add(ROCK);
        int sends = peer.sends.get();
        assertEquals(202, b.submit(input, "parsed-curated").status());
        b.awaitState(s -> s.path("status").asText().equals("SUCCEEDED"));
        assertEquals("CURATED", b.state.path("analysis_mode").asText());
        assertEquals(sends + 1, peer.sends.get());
        assertTrue(
                peer.jobs.values().stream()
                        .noneMatch(
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
