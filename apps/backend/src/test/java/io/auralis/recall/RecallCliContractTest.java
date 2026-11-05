package io.auralis.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "AURALIS_RECALL_PYTHON", matches = ".+")
class RecallCliContractTest {
    @Test
    void javaConsumesRealPythonResultAgainstSharedSchemas() throws Exception {
        var intelligence = Path.of(System.getProperty("basedir", "."))
                .toAbsolutePath().resolveSibling("intelligence");
        var source = intelligence.resolve("src");
        var schemas = source.resolve("auralis_intelligence/recall");
        var fixture = intelligence.resolve("tests/fixtures");
        var request = fixture.resolve("request.json");
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        var json = new ObjectMapper();
        try (var schema = Files.newInputStream(schemas.resolve("request-v1.schema.json"))) {
            assertTrue(factory.getSchema(schema).validate(json.readTree(request.toFile())).isEmpty());
        }

        var command = new ProcessBuilder(List.of(
                System.getenv("AURALIS_RECALL_PYTHON"), "-m", "auralis_intelligence.recall",
                "--snapshot", fixture.resolve("synthetic-snapshot.json").toString(),
                "--request", request.toString(), "--special-weight", "2", "--focus-weight", "2"));
        command.environment().put("PYTHONPATH", source.toString());
        command.redirectError(ProcessBuilder.Redirect.INHERIT);
        var process = command.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "local recall fixture timed out");
            assertEquals(0, process.exitValue());
            var result = json.readTree(process.getInputStream());
            try (var schema = Files.newInputStream(schemas.resolve("result-v1.schema.json"))) {
                assertTrue(factory.getSchema(schema).validate(result).isEmpty());
            }
            assertEquals("A-V2", result.path("algorithm").asText());
            assertEquals("a1", result.path("candidate_ids").path(0).asText());
            assertEquals(2, result.path("returned_count").asInt());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
