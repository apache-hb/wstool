package wstool.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildOptionsTest {
    @Test
    void declaredBuildsAlwaysUseScratch() {
        var options = new BuildOptions("isolated", "readonly", "declared", "ubuntu:24.04");

        assertEquals("scratch", options.effectiveDockerImage());
    }

    @Test
    void bootstrapBuildsUseConfiguredDockerImage() {
        var options = new BuildOptions("isolated", "readonly", "bootstrap", "ubuntu:24.04");

        assertEquals("ubuntu:24.04", options.effectiveDockerImage());
    }
}
