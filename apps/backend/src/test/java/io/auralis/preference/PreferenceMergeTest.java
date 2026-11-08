package io.auralis.preference;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import io.auralis.bootstrap.WireProtocol;
import io.auralis.preference.api.PreferenceRejected;
import io.auralis.preference.application.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PreferenceMergeTest {
    @Test
    void sameInputsMatchActualPythonFieldForFieldIncludingErrorsAndDigests() throws Exception {
        Path intelligence = Path.of(System.getProperty("basedir")).resolveSibling("intelligence");
        Path cases = intelligence.resolve("tests/fixtures/preference-authority-cases.json");
        var fixture = JSON.readTree(cases.toFile());
        var referenceDoc =
                JSON.readTree(
                                cases.getParent()
                                        .resolve(fixture.path("reference_fixture").asText())
                                        .toFile())
                        .path("reference");
        var reference = PreferenceReference.verified(referenceDoc, digest(referenceDoc));
        var engine = new PreferenceMerge(new WireProtocol(JSON));
        String python = System.getenv("AURALIS_RECALL_PYTHON");
        if (python == null) {
            var windows = intelligence.resolve(".venv/Scripts/python.exe");
            python =
                    (Files.exists(windows) ? windows : intelligence.resolve(".venv/bin/python"))
                            .toString();
        }
        assertTrue(Files.isExecutable(Path.of(python)), "Set AURALIS_RECALL_PYTHON");
        Path output = Files.createTempFile("be008-python-", ".json");
        var process =
                new ProcessBuilder(
                        python,
                        intelligence.resolve("tests/preference_wire.py").toString(),
                        cases.toString());
        process.environment().put("PYTHONPATH", intelligence.resolve("src").toString());
        process.redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(output.toFile());
        var child = process.start();
        assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        var expected = JSON.readTree(output.toFile());
        Files.delete(output);
        int i = 0;
        for (var row : fixture.path("cases")) {
            var actual = JSON.createObjectNode().put("name", row.path("name").asText());
            var descriptions = new HashMap<String, String>();
            row.path("seed_descriptions")
                    .fields()
                    .forEachRemaining(e -> descriptions.put(e.getKey(), e.getValue().asText()));
            try {
                actual.set(
                        "result",
                        engine.merge(
                                row.path("raw"),
                                row.path("proposal"),
                                reference.revision(),
                                reference.names(),
                                descriptions));
            } catch (PreferenceRejected error) {
                actual.put("error", error.getMessage()).put("outcome", error.outcome());
            }
            assertEquals(expected.get(i++), actual, row.path("name").asText());
            if (row.has("expected_tag"))
                assertEquals(row.path("expected_tag"), actual.path("result").path("tags").get(0));
        }
        assertEquals(39, i);
    }

    @Test
    void referenceMustMatchApprovedDigestAndNames() throws Exception {
        var doc = JSON.readTree("{\"genres\":[],\"branches\":[]}");
        assertThrows(
                PreferenceRejected.class,
                () -> PreferenceReference.verified(doc, "sha256:" + "0".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> digest(JSON.readTree("-9223372036854775808")));
    }
}
