package io.auralisy.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Same schema and fixtures as Python; no Java product service is implied. */
class AgentWireContractTest {
    @Test
    void consumesSharedMessagesAndActualPythonSerialization() throws Exception {
        roundTrip("agent-contract-cases.json", false);
    }

    @Test
    void consumesJavaIntegrationDefinitionsAndActualPythonSerialization() throws Exception {
        roundTrip("java-integration-cases.json", true);
    }

    private void roundTrip(String fixture, boolean definitions) throws Exception {
        var intelligence =
                Path.of(System.getProperty("basedir", "."))
                        .toAbsolutePath()
                        .resolveSibling("intelligence");
        var json = new ObjectMapper();
        var cases = json.readTree(intelligence.resolve("tests/fixtures/" + fixture).toFile());
        var schemaPath =
                intelligence.resolve("src/auralisy_intelligence/contracts/agent-v1.schema.json");
        try (var stream = Files.newInputStream(schemaPath)) {
            var document = (ObjectNode) json.readTree(stream);
            var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            for (var item : cases) {
                var schema = factory.getSchema(definitionDocument(document, item, definitions));
                assertEquals(
                        item.path("structural_valid").asBoolean(),
                        schema.validate(item.path(definitions ? "payload" : "message")).isEmpty(),
                        item.path("name").asText());
            }
            var python = System.getenv("AURALIS_RECALL_PYTHON");
            if (python == null) {
                var windows = intelligence.resolve(".venv/Scripts/python.exe");
                python =
                        Files.exists(windows)
                                ? windows.toString()
                                : intelligence.resolve(".venv/bin/python").toString();
            }
            assertTrue(
                    Files.isExecutable(Path.of(python)),
                    "Set AURALIS_RECALL_PYTHON to the intelligence environment");
            var command = new ArrayList<>(List.of(python, "-m", "auralisy_intelligence.contracts"));
            if (definitions) command.add("--definitions");
            var builder = new ProcessBuilder(command);
            builder.environment().put("PYTHONPATH", intelligence.resolve("src").toString());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            var responseFile = Files.createTempFile("auralis-contract-", ".json");
            builder.redirectOutput(responseFile.toFile());
            var process = builder.start();
            try {
                ArrayNode requests = json.createArrayNode();
                for (var item : cases) {
                    if (definitions) {
                        var probe = json.createObjectNode();
                        probe.set("definition", item.path("definition"));
                        probe.set("payload", item.path("payload"));
                        requests.add(probe);
                    } else requests.add(item.path("message"));
                }
                try (var output = process.getOutputStream()) {
                    json.writeValue(output, requests);
                }
                assertTrue(
                        process.waitFor(30, TimeUnit.SECONDS), "Python contract probe timed out");
                assertEquals(0, process.exitValue());
                var responses = json.readTree(responseFile.toFile());
                assertEquals(cases.size(), responses.size());
                for (int i = 0; i < cases.size(); i++) {
                    var response = responses.get(i);
                    assertEquals(
                            cases.get(i).path("semantic_valid").asBoolean(),
                            response.path("accepted").asBoolean(),
                            cases.get(i).path("name").asText());
                    if (response.path("accepted").asBoolean()) {
                        var schema =
                                factory.getSchema(
                                        definitionDocument(document, cases.get(i), definitions));
                        var payload =
                                definitions
                                        ? response.path("message").path("payload")
                                        : response.path("message");
                        assertTrue(schema.validate(payload).isEmpty());
                        assertEquals(requests.get(i), response.path("message"));
                    }
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
                Files.deleteIfExists(responseFile);
            }
        }
    }

    private ObjectNode definitionDocument(ObjectNode document, JsonNode item, boolean definitions) {
        var result = document.deepCopy();
        if (definitions) result.put("$ref", "#/$defs/" + item.path("definition").asText());
        return result;
    }

    @Test
    void convertsUnicodeCodePointsWithoutNormalizingOriginalText() throws Exception {
        var fixture =
                Path.of(System.getProperty("basedir", "."))
                        .toAbsolutePath()
                        .resolveSibling("intelligence/tests/fixtures/codepoint-cases.json");
        for (var item : new ObjectMapper().readTree(fixture.toFile())) {
            var text = item.path("text").asText();
            var start = item.path("span").path("start").asInt();
            var end = item.path("span").path("end").asInt();
            boolean valid =
                    start >= 0 && start < end && end <= text.codePointCount(0, text.length());
            if (valid) {
                var extracted =
                        text.substring(
                                text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
                valid = extracted.equals(item.path("expected").asText());
            }
            assertEquals(item.path("valid").asBoolean(), valid, item.path("name").asText());
        }
    }
}
