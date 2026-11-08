package io.auralis.coredata;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreDatabaseTest {
    @TempDir Path directory;

    @Test
    void traversalCannotSelectExternalFile() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreDatabase.contained(directory, "../secret.tsv"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreDatabase.contained(directory, "C:\\secret.tsv"));
    }

    @Test
    void duplicateJsonKeysAreRejected() {
        assertThrows(
                Exception.class,
                () -> CoreDatabase.JSON.readTree("{\"enabled\":false,\"enabled\":true}"));
    }

    @Test
    void manifestRequiresActualBoundFiles() throws Exception {
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, "{\"format_version\":1,\"purpose\":\"private-development\"}");
        assertThrows(IllegalArgumentException.class, () -> CoreDatabase.manifest(manifest));
    }

    @Test
    void admissionDoesNotTrustSelfDeclaredSource() throws Exception {
        Path policy = directory.resolve("policy.json");
        Files.writeString(
                policy,
                "{\"enabled\":true,\"purpose\":\"private-development\",\"musicbrainz\":{\"snapshot\":\"approved\"}}");
        var manifest =
                CoreDatabase.JSON.readTree(
                        "{\"sources\":{\"musicbrainz\":{\"snapshot\":\"other\"}}}");
        assertThrows(
                IllegalArgumentException.class, () -> CoreDatabase.admission(manifest, policy));
    }

    @Test
    void sourceIdentityCheckDoesNotRequireLicenseFlags() throws Exception {
        Path source = directory.resolve("source.json");
        Files.writeString(source, "{\"musicbrainz\":{},\"listenbrainz\":{}}");
        var manifest =
                CoreDatabase.JSON.readTree(
                        "{\"sources\":{\"musicbrainz\":{},\"listenbrainz\":{\"members\":{}}}}");
        assertNotNull(CoreDatabase.admission(manifest, source));
    }
}
