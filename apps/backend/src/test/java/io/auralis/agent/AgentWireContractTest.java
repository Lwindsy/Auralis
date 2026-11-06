package io.auralis.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Same schema and fixtures as Python; no Java product service is implied. */
class AgentWireContractTest {
    @Test
    void consumesSharedMessagesAndActualPythonSerialization() throws Exception {
        var intelligence = Path.of(System.getProperty("basedir", "."))
                .toAbsolutePath().resolveSibling("intelligence");
        var json = new ObjectMapper();
        var cases = json.readTree(intelligence.resolve("tests/fixtures/agent-contract-cases.json").toFile());
        var schemaPath = intelligence.resolve("src/auralis_intelligence/contracts/agent-v1.schema.json");
        try (var stream = Files.newInputStream(schemaPath)) {
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(stream);
            for (var item : cases) {
                assertEquals(item.path("structural_valid").asBoolean(), schema.validate(item.path("message")).isEmpty(), item.path("name").asText());
            }
            var python = System.getenv("AURALIS_RECALL_PYTHON");
            if (python == null) {
                var windows = intelligence.resolve(".venv/Scripts/python.exe");
                python = Files.exists(windows) ? windows.toString() : intelligence.resolve(".venv/bin/python").toString();
            }
            assertTrue(Files.isExecutable(Path.of(python)), "Set AURALIS_RECALL_PYTHON to the intelligence environment");
            var builder = new ProcessBuilder(List.of(python, "-m", "auralis_intelligence.contracts"));
            builder.environment().put("PYTHONPATH", intelligence.resolve("src").toString());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            var responseFile = Files.createTempFile("auralis-contract-", ".json");
            builder.redirectOutput(responseFile.toFile());
            var process = builder.start();
            try {
                ArrayNode requests = json.createArrayNode();
                for (var item : cases) requests.add(item.path("message"));
                try (var output = process.getOutputStream()) { json.writeValue(output, requests); }
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Python contract probe timed out");
                assertEquals(0, process.exitValue());
                var responses = json.readTree(responseFile.toFile());
                assertEquals(cases.size(), responses.size());
                for (int i = 0; i < cases.size(); i++) {
                    var response = responses.get(i);
                    assertEquals(cases.get(i).path("semantic_valid").asBoolean(), response.path("accepted").asBoolean(), cases.get(i).path("name").asText());
                    if (response.path("accepted").asBoolean()) {
                        assertTrue(schema.validate(response.path("message")).isEmpty());
                        assertEquals(cases.get(i).path("message"), response.path("message"));
                    }
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
                Files.deleteIfExists(responseFile);
            }
        }
    }
}
