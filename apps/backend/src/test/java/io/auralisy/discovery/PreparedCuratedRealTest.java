package io.auralisy.discovery;

import static io.auralisy.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.auralisy.catalog.adapters.LuceneCatalogSearch;
import io.auralisy.catalog.api.*;
import io.auralisy.catalog.application.*;
import io.auralisy.discovery.adapters.*;
import io.auralisy.publication.api.PublicationAuthority;
import io.auralisy.session.api.SessionStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real supplied content + full catalogue files; session/core adapters remain isolated substitutes.
 */
@EnabledIfEnvironmentVariable(named = "AURALIS_CURATED_TEST_CONTENT", matches = ".+")
class PreparedCuratedRealTest {
    @Test
    void allTenRealAlbumsPassIdentityAndExistingProductReviewContracts() throws Exception {
        var content =
                CuratedContentFiles.load(Path.of(System.getenv("AURALIS_CURATED_TEST_CONTENT")));
        var index = Path.of(System.getenv("AURALIS_CURATED_TEST_INDEX"));
        try (var search =
                new LuceneCatalogSearch(
                        index, Files.readString(index.resolve("manifest.sha256")))) {
            var nodes = new HashMap<String, GenreQuery.Node>();
            for (var album : content.albums()) {
                var identity =
                        search.resolve(UUID.fromString(album.musicbrainz_release_group_id()));
                assertEquals(album.release_year(), identity.year());
                for (var genre : identity.genres())
                    nodes.put(
                            genre.genreId(),
                            new GenreQuery.Node(
                                    genre.genreId(),
                                    genre.name(),
                                    Set.of(),
                                    Set.of(),
                                    false,
                                    null));
            }
            var genres = mock(GenreQuery.class);
            when(genres.nodes(eq(1L), anySet()))
                    .thenAnswer(
                            call -> {
                                var result = new HashMap<String, GenreQuery.Node>();
                                for (String id : (Set<String>) call.getArgument(1))
                                    if (nodes.containsKey(id)) result.put(id, nodes.get(id));
                                return result;
                            });
            when(genres.descendantGroups(eq(1L), anySet())).thenReturn(Map.of());
            CatalogVersion versions = () -> 1L;
            var directory =
                    new CatalogDirectory(
                            search,
                            (dataset, ids) -> Map.of(),
                            genres,
                            versions,
                            (name, value) -> {
                                CatalogJson.require(name, value);
                                return true;
                            });
            var sessions = mock(SessionStore.class);
            var publication = mock(PublicationAuthority.class);
            var scope = mock(SessionStore.Scope.class);
            when(sessions.heardAlbums(scope)).thenReturn(List.of());
            when(publication.exclusions(scope, "round")).thenReturn("[]");
            var provider =
                    new PreparedCuratedRecommendations(
                            content, directory, versions, genres, sessions, publication);
            var result = provider.candidates(scope, "round", "{\"has_positive_signal\":false}");
            assertEquals(
                    content.albums().stream().map(a -> a.musicbrainz_release_group_id()).toList(),
                    result.items().stream().map(i -> i.id()).toList(),
                    result.diagnostics());
            for (var item : result.items()) {
                CatalogJson.require("Identity", JSON.readTree(item.identity()));
                CatalogJson.require("ShortReview", JSON.readTree(item.shortReview()));
                CatalogJson.require("LongReview", JSON.readTree(item.longReview()));
            }
        }
    }
}
