package io.auralisy.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.auralisy.bootstrap.WireProtocol;
import io.auralisy.catalog.api.*;
import io.auralisy.catalog.application.CatalogDirectory;
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
        when(remote.search("album", 0, 50))
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
        when(remote.search(valid, 0, 50)).thenReturn(new CatalogSearchPort.Page(List.of(), null));
        assertTrue(directory.search(valid, 0, 20).path("items").isEmpty());
    }

    @Test
    void singlesAreGatedAfterCoreResolutionAndPaginationRefillsAcrossIndexPages() {
        var rows = new ArrayList<CatalogSearchPort.Album>();
        for (int i = 0; i < 54; i++)
            rows.add(
                    new CatalogSearchPort.Album(
                            new UUID(0, i + 1),
                            "Release " + i,
                            "Artist",
                            null,
                            i < 50 ? "Single" : i == 51 ? "EP" : "Album",
                            List.of(),
                            0));
        // The core catalog can identify a Single even when the search snapshot says Album.
        when(local.albums(anyLong(), anyList()))
                .thenAnswer(
                        call -> {
                            List<UUID> ids = call.getArgument(1);
                            UUID id = rows.get(52).id();
                            return ids.contains(id)
                                    ? Map.of(
                                            id,
                                            new CatalogQuery.Album(
                                                    id,
                                                    "Core Single",
                                                    "Artist",
                                                    null,
                                                    "Single",
                                                    List.of(),
                                                    List.of()))
                                    : Map.of();
                        });
        when(remote.search(eq("Artist"), anyInt(), eq(50)))
                .thenAnswer(
                        call -> {
                            int offset = call.getArgument(1);
                            int end = Math.min(offset + 50, rows.size());
                            return new CatalogSearchPort.Page(
                                    rows.subList(offset, end), end < rows.size() ? end : null);
                        });
        var first = directory.search("Artist", 0, 2);
        assertEquals(2, first.path("items").size());
        assertEquals(
                rows.get(50).id().toString(), first.at("/items/0/mb_release_group_id").asText());
        assertEquals("EP", first.at("/items/1/identity/release_type").asText());
        assertEquals(53, first.path("next_offset").asInt());
        var second = directory.search("Artist", first.path("next_offset").asInt(), 2);
        assertEquals(1, second.path("items").size());
        assertEquals(
                rows.get(53).id().toString(), second.at("/items/0/mb_release_group_id").asText());
        assertTrue(second.path("next_offset").isNull());
    }

    @Test
    void singleOnlySearchIsEmptyEvenForAnExactTitleAndUnknownTypesRemainVisible() {
        var single =
                new CatalogSearchPort.Album(album, "Exact", "Artist", null, "single", List.of(), 0);
        when(remote.search("Exact", 0, 50))
                .thenReturn(new CatalogSearchPort.Page(List.of(single), null));
        var empty = directory.search("Exact", 0, 20);
        assertTrue(empty.path("items").isEmpty());
        assertTrue(empty.path("next_offset").isNull());
        when(remote.search("unknown", 0, 50))
                .thenReturn(new CatalogSearchPort.Page(List.of(external), null));
        assertEquals(1, directory.search("unknown", 0, 20).path("items").size());
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
