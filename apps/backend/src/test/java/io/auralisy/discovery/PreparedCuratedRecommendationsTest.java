package io.auralisy.discovery;

import static io.auralisy.contracts.CanonicalJson.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.auralisy.catalog.api.*;
import io.auralisy.catalog.application.CatalogDirectory;
import io.auralisy.discovery.adapters.PreparedCuratedRecommendations;
import io.auralisy.discovery.api.CuratedContent;
import io.auralisy.publication.api.PublicationAuthority;
import io.auralisy.session.api.SessionStore;
import java.util.*;
import org.junit.jupiter.api.Test;

class PreparedCuratedRecommendationsTest {
    @Test
    void offlinePoolKeepsOrderFiltersConstraintsAndNeedsNoAudienceProvider() throws Exception {
        var directory = mock(CatalogDirectory.class);
        var versions = mock(CatalogVersion.class);
        var genres = mock(GenreQuery.class);
        var sessions = mock(SessionStore.class);
        var publication = mock(PublicationAuthority.class);
        when(versions.current()).thenReturn(1L);
        when(genres.descendantGroups(eq(1L), anySet()))
                .thenAnswer(
                        call -> {
                            var result = new HashMap<String, Set<String>>();
                            for (String root : (Set<String>) call.getArgument(1))
                                result.put(root, Set.of(root));
                            return result;
                        });
        when(publication.exclusions(any(), eq("round"))).thenReturn("[]");
        var albums = new ArrayList<CuratedContent.Album>();
        var ids = new ArrayList<UUID>();
        for (var original : CuratedContentFilesTest.content().albums()) {
            UUID id =
                    UUID.nameUUIDFromBytes(
                            original.key().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ids.add(id);
            albums.add(
                    new CuratedContent.Album(
                            original.key(),
                            original.title(),
                            original.artist(),
                            2000,
                            id.toString(),
                            original.identity_urls(),
                            original.genres(),
                            original.review_status(),
                            original.short_review(),
                            original.long_review(),
                            original.sources(),
                            original.missing()));
            var resolved = JSON.createObjectNode();
            var album = resolved.putObject("album").put("mb_release_group_id", id.toString());
            var identity =
                    album.putObject("identity")
                            .put("title", original.title())
                            .put("artist_credit", original.artist())
                            .put("first_release_year", 2000)
                            .put("release_type", "Album");
            identity.putArray("primary_genres")
                    .addObject()
                    .put("genre_id", ids.size() == 2 ? "rock" : "jazz")
                    .put("name", "测试风格");
            when(directory.resolve(id)).thenReturn(resolved);
        }
        var scope = mock(SessionStore.Scope.class);
        when(sessions.heardAlbums(scope)).thenReturn(List.of(ids.getFirst()));
        var provider =
                new PreparedCuratedRecommendations(
                        new CuratedContent("auralis-curated-v1", albums),
                        directory,
                        versions,
                        genres,
                        sessions,
                        publication);
        String pref =
                "{\"has_positive_signal\":false,\"forbidden_tags\":[\"rock\"],\"required_tags\":[],\"disliked_tags\":[]}";
        var batch = provider.candidates(scope, "round", pref);
        assertEquals(
                ids.subList(2, 10).stream().map(UUID::toString).toList(),
                batch.items().stream().map(v -> v.id()).toList());
        assertTrue(batch.diagnostics().contains("EXCLUDED"));
        assertTrue(batch.diagnostics().contains("HARD_CONSTRAINT"));
        assertEquals(
                "READY",
                JSON.readTree(batch.items().getFirst().longReview()).path("status").asText());
        assertThrows(
                io.auralisy.persistence.PersistenceRejected.class,
                () -> provider.candidates(scope, "round", "{\"has_positive_signal\":true}"));
        var empty = provider.candidates(scope, "round", pref.replace("rock", "jazz"));
        assertEquals(
                List.of(ids.get(1).toString()), empty.items().stream().map(v -> v.id()).toList());
    }
}
