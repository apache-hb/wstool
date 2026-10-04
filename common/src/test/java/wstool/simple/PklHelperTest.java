package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pkl.config.java.Config;

public class PklHelperTest {
    @TempDir Path tempDir;

    @Test
    void evaluatesModuleUsingProvidedEnvironmentVariables() throws Exception {
        Path module = tempDir.resolve("config.pkl");
        Files.writeString(module, "answer = read(\"env:HELPER_TEST_VALUE\")\n");

        try (PklHelper helper = new PklHelper(Map.of("HELPER_TEST_VALUE", "42"))) {
            Config config = helper.evaluate(module);
            assertEquals("42", config.get("answer").as(String.class));
        }
    }

    @Test
    void throwsWhenModuleCannotBeEvaluated() throws Exception {
        Path module = tempDir.resolve("invalid.pkl");
        Files.writeString(module, "answer = missingName\n");

        try (PklHelper helper = new PklHelper(Map.of())) {
            assertThrows(RuntimeException.class, () -> helper.evaluate(module));
        }
    }
}
