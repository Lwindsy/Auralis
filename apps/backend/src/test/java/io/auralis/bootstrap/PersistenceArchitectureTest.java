package io.auralis.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PersistenceArchitectureTest {
    @Test
    void modulesWriteOnlyOwnedTablesAndDoNotImportOtherAdaptersOrNetworkClients() throws Exception {
        var owners =
                Map.of(
                        "session",
                                Set.of(
                                        "subject",
                                        "session",
                                        "heard_album",
                                        "command_receipt",
                                        "deletion_job",
                                        "subject_revocation"),
                        "discovery",
                                Set.of(
                                        "recommendation_round",
                                        "discovery_intent",
                                        "runtime_control",
                                        "execution_slot",
                                        "execution",
                                        "dispatch_command",
                                        "execution_command"),
                        "budget", Set.of("budget_scope", "invocation", "usage_receipt"),
                        "evidence",
                                Set.of(
                                        "read_claim",
                                        "read_receipt",
                                        "seed_evidence",
                                        "read_ticket",
                                        "seed_read_binding"),
                        "preference", Set.of("effective_preference"),
                        "publication",
                                Set.of(
                                        "round_candidate",
                                        "recommendation_card",
                                        "card_slot",
                                        "card_undo",
                                        "long_review",
                                        "delivery_receipt",
                                        "recovery_projection",
                                        "publication_round",
                                        "publication_run_binding"),
                        "catalog", Set.of("audience_snapshot", "audience_count"));
        Path source = Path.of(System.getProperty("basedir"), "src/main/java/io/auralis");
        for (var entry : owners.entrySet()) {
            try (var paths = Files.walk(source.resolve(entry.getKey()))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(path);
                    var imports =
                            Pattern.compile(
                                            "import io\\.auralis\\.([a-z]+)\\.(adapters|internal)\\.")
                                    .matcher(text);
                    while (imports.find())
                        assertEquals(entry.getKey(), imports.group(1), path.toString());
                    assertFalse(
                            // URI parses source identifiers without opening a network connection.
                            text.replace("import java.net.URI;", "").contains("java.net.")
                                    || text.contains("WebClient")
                                    || text.contains("RestClient")
                                    || text.contains("RestTemplate"),
                            path.toString());
                    var writes =
                            Pattern.compile(
                                            "(?:INSERT INTO|UPDATE|DELETE FROM) discovery\\.([a-z_]+)")
                                    .matcher(text);
                    while (writes.find())
                        assertTrue(
                                entry.getValue().contains(writes.group(1)),
                                path + " writes " + writes.group(1));
                    if (path.getParent().getFileName().toString().equals("api")) {
                        assertFalse(
                                text.contains("import org.springframework")
                                        || text.contains("import jakarta.persistence"),
                                path.toString());
                    }
                }
            }
        }
    }
}
