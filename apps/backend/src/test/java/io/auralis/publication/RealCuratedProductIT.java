package io.auralis.publication;

import static org.junit.jupiter.api.Assertions.*;

import io.auralis.discovery.adapters.CuratedContentFiles;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real uploaded reviews and full index over HTTP; all personal writes use the isolated fixture DB.
 */
@EnabledIfEnvironmentVariable(named = "AURALIS_CURATED_PRODUCT_TEST_CONTENT", matches = ".+")
class RealCuratedProductIT extends ProductApiFixture {
    @Override
    com.fasterxml.jackson.databind.node.ObjectNode configuration() throws Exception {
        var settings = super.configuration();
        ((com.fasterxml.jackson.databind.node.ObjectNode) settings.path("selection_config"))
                .put("recommendation_target_count", 3);
        ((com.fasterxml.jackson.databind.node.ObjectNode) settings.path("stage_capacity"))
                .put("recommendation_target_count", 3);
        return settings;
    }

    @Test
    void servesTenRealCardsAndStoredLongReviewsThenExhaustsWithoutPython() throws Exception {
        var content =
                CuratedContentFiles.load(
                        Path.of(System.getenv("AURALIS_CURATED_PRODUCT_TEST_CONTENT")));
        var b = browser();
        var input = userInput("");
        input.putArray("liked_genres");
        input.putArray("disliked_genre_ids").add(ROCK);
        int sends = peer.sends.get();
        assertEquals(200, b.submit(input, "real-curated").status());
        var shown = new ArrayList<String>();
        int batch = 0;
        while (!b.state.path("status").asText().equals("NO_MATCH")) {
            for (var slot : b.state.path("slots")) {
                var card = slot.path("card");
                if (card.isNull()) continue;
                String id = card.path("candidate_id").asText();
                shown.add(id);
                var expected =
                        content.albums().stream()
                                .filter(a -> id.equals(a.musicbrainz_release_group_id()))
                                .findFirst()
                                .orElseThrow();
                assertEquals(
                        expected.short_review().text(),
                        card.path("short_review").path("text").asText());
                var review =
                        b.request(
                                "GET",
                                "/v1/discovery-sessions/" + b.session() + "/long-reviews/" + id,
                                null,
                                null);
                assertEquals(200, review.status());
                assertEquals("READY", review.body().path("status").asText());
                assertEquals(
                        expected.long_review().text(),
                        review.body().path("review").path("sections").path("production").asText());
            }
            assertTrue(batch < 6);
            var next = b.action("next-batch", null, "real-batch-" + batch++);
            assertEquals(200, next.status(), next.body().toString());
        }
        assertEquals(
                content.albums().stream().map(a -> a.musicbrainz_release_group_id()).toList(),
                shown);
        assertEquals("FINAL_EXHAUSTED", b.state.path("supply_state").asText());
        assertEquals(sends, peer.sends.get());
        assertTrue(peer.failures.isEmpty(), peer.failures.toString());
    }
}
