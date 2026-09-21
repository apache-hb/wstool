package wstool.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildOptionsTest {
    @Test
    void declaredBuildsAlwaysUseScratch() {
        var options =
                new BuildOptions(
                        "tool",
                        Map.of(),
                        Map.of(),
                        "isolated",
                        "readonly",
                        "declared",
                        "ubuntu:24.04");

        assertEquals("scratch", options.effectiveDockerImage());
    }

    @Test
    void bootstrapBuildsUseConfiguredDockerImage() {
        var options =
                new BuildOptions(
                        "tool",
                        Map.of(),
                        Map.of(),
                        "isolated",
                        "readonly",
                        "bootstrap",
                        "ubuntu:24.04");

        assertEquals("ubuntu:24.04", options.effectiveDockerImage());
    }
}
