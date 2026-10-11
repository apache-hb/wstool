package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pkl.config.java.Config;

public class PklHelperTest {
    @Test
    void evaluatesModuleUsingProvidedEnvironmentVariables() throws Exception {
        Path module = resourcePath("pkl-helper/environment-value.pkl");

        try (PklHelper helper = new PklHelper(Map.of("HELPER_TEST_VALUE", "42"))) {
            Config config = helper.evaluate(module);
            assertEquals("42", config.get("answer").as(String.class));
        }
    }

    @Test
    void throwsWhenModuleCannotBeEvaluated() throws Exception {
        Path module = resourcePath("pkl-helper/invalid.pkl");

        try (PklHelper helper = new PklHelper(Map.of())) {
            assertThrows(RuntimeException.class, () -> helper.evaluate(module));
        }
    }

    private Path resourcePath(String name) throws Exception {
        var resource = getClass().getClassLoader().getResource(name);
        if (resource == null) {
            throw new IllegalArgumentException("Missing test resource: " + name);
        }
        return Path.of(resource.toURI());
    }
}
