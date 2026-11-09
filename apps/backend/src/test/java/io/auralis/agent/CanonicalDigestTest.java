package io.auralis.agent;

import static io.auralis.contracts.CanonicalJson.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CanonicalDigestTest {
    @Test
    void numericPoliciesMatchActualPythonRfc8785() throws Exception {
        var values =
                JSON.readTree(
                        """
                [{"weights":{"special":2.0,"focus":2.5}},
                 {"values":[-0.0,1e-7,1e-6,1e20,1e21,333333333.33333329]},
                 {"text":"爵士🎵\\n\\t\\u001f","value":1.25}]
                """);
        String python = System.getenv("AURALIS_RECALL_PYTHON");
        if (python == null) {
            Path intelligence =
                    Path.of(System.getProperty("basedir")).resolveSibling("intelligence");
            Path windows = intelligence.resolve(".venv/Scripts/python.exe");
            python =
                    (Files.exists(windows) ? windows : intelligence.resolve(".venv/bin/python"))
                            .toString();
        }
        var process =
                new ProcessBuilder(
                                python,
                                "-X",
                                "utf8",
                                "-c",
                                "import json,sys,rfc8785,hashlib; print(json.dumps(['sha256:'+hashlib.sha256(rfc8785.dumps(v)).hexdigest() for v in json.load(sys.stdin)]))")
                        .start();
        try {
            process.getOutputStream().write(JSON.writeValueAsBytes(values));
            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            var expected = JSON.readTree(process.getInputStream());
            for (int i = 0; i < values.size(); i++)
                assertEquals(expected.get(i).asText(), digest(values.get(i)));
            assertEquals(
                    digest(JSON.readTree("{\"value\":2}")),
                    digest(JSON.readTree("{\"value\":2.0}")));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void unsafeIntegersAndNonFiniteNumbersRemainRejected() throws Exception {
        assertThrows(
                IllegalArgumentException.class, () -> digest(JSON.readTree("9007199254740992")));
        assertThrows(
                IllegalArgumentException.class,
                () -> digest(JSON.getNodeFactory().numberNode(Double.NaN)));
    }
}
