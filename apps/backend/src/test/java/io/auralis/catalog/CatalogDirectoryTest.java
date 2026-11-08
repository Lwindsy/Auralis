package io.auralis.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.auralis.bootstrap.WireProtocol;
import io.auralis.catalog.api.*;
import io.auralis.catalog.application.CatalogDirectory;
import java.util.*;
import org.junit.jupiter.api.*;

class CatalogDirectoryTest {
    final UUID album = UUID.fromString("11111111-1111-4111-8111-111111111111");
    final String genre = "22222222-2222-4222-8222-222222222222";
    CatalogSearchPort remote;
    CatalogQuery local;
    GenreQuery genres;
    CatalogVersion version;
    CatalogDirectory directory;
    CatalogSearchPort.Album external;

    @BeforeEach
    void setup() throws Exception {
        remote = mock(CatalogSearchPort.class);
        local = mock(CatalogQuery.class);
        genres = mock(GenreQuery.class);
        version = mock(CatalogVersion.class);
        when(version.current()).thenReturn(1L);
        when(local.albums(anyLong(), anyList())).thenReturn(Map.of());
        when(genres.nodes(anyLong(), anySet())).thenReturn(Map.of());
        external =
                new CatalogSearchPort.Album(
                        album,
                        "Outside",
                        "Artist",
                        null,
                        null,
                        List.of(new CatalogQuery.Genre(genre, "Rock")),
                        100);
        when(remote.resolve(album)).thenReturn(external);
        directory =
                new CatalogDirectory(
                        remote, local, genres, version, new WireProtocol(new ObjectMapper()));
    }

    @Test
    void unknownAlbumWithoutGenresIsSelectableButNeverACoreSeed() {
        var selected = directory.resolve(album);
        assertTrue(selected.at("/input/release_group_id").isNull());
        assertEquals(album.toString(), selected.at("/input/external/mb_release_group_id").asText());
        assertTrue(selected.at("/input/external/mapped_genre_ids").isEmpty());
        assertFalse(selected.at("/album/in_core_catalog").asBoolean());
    }

    @Test
    void onlyAdmittedGenreIdsMapAndCoreRedirectUsesCanonicalIdentity() {
        when(genres.nodes(1L, Set.of(genre)))
                .thenReturn(
                        Map.of(
                                genre,
                                new GenreQuery.Node(genre, "摇滚", Set.of(), Set.of(), false, null)));
        assertEquals(
                genre, directory.resolve(album).at("/input/external/mapped_genre_ids/0").asText());
        UUID canonical = UUID.randomUUID();
        when(local.albums(1L, List.of(album)))
                .thenReturn(
                        Map.of(
                                album,
                                new CatalogQuery.Album(
                                        canonical,
                                        "Canonical",
                                        "Verified artist",
                                        2000,
                                        "Album",
                                        List.of(),
                                        List.of())));
        var selected = directory.resolve(album);
        assertEquals(canonical.toString(), selected.at("/input/release_group_id").asText());
        assertTrue(selected.at("/input/external").isNull());
        assertEquals("Canonical", selected.at("/album/identity/title").asText());
    }

    @Test
    void duplicateRowsCollapseAndVersionChangeRejectsMixedData() {
        when(remote.search("album", 0, 20))
                .thenReturn(new CatalogSearchPort.Page(List.of(external, external), null));
        assertEquals(1, directory.search("album", 0, 20).path("items").size());
        when(version.current()).thenReturn(1L, 2L);
        assertEquals(
                "CATALOG_VERSION_CHANGED",
                assertThrows(CatalogFailure.class, () -> directory.resolve(album)).getMessage());
    }

    @Test
    void invalidRequestsNeverReachUpstreamAndUnicodeLimitsCountCodepoints() {
        for (String bad : List.of(" ", "x".repeat(201)))
            assertThrows(CatalogFailure.class, () -> directory.search(bad, 0, 20));
        assertThrows(CatalogFailure.class, () -> directory.search("x", 10001, 20));
        assertThrows(CatalogFailure.class, () -> directory.search("x", 0, 51));
        verifyNoInteractions(remote);
        String valid = "🎵".repeat(200);
        when(remote.search(valid, 0, 20)).thenReturn(new CatalogSearchPort.Page(List.of(), null));
        assertTrue(directory.search(valid, 0, 20).path("items").isEmpty());
    }

    @Test
    void genresBrowseRootsChildrenAliasesAndPaginationWithoutExternalCalls() {
        var node = new GenreQuery.Node(genre, "Rock", Set.of("摇滚"), Set.of(), true, null);
        when(genres.search(1L, null, null, 0, 1)).thenReturn(List.of(node));
        when(genres.search(1L, null, null, 1, 1)).thenReturn(List.of(node));
        assertEquals(1, directory.genres(null, null, 0, 1).path("next_offset").asInt());
        when(genres.search(1L, "摇滚", null, 0, 20)).thenReturn(List.of(node));
        assertEquals(genre, directory.genres("摇滚", null, 0, 20).at("/items/0/genre_id").asText());
        verifyNoInteractions(remote);
    }
}
