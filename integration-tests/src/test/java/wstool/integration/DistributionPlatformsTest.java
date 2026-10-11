package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import wstool.simple.DistributionHelper;
import wstool.simple.PklHelper;

class DistributionPlatformsTest {
    @Test
    void acceptsOneDistributionBuildPlatform() throws Exception {
        Path distribution = resourcePath("distribution-platforms/single-platform.pkl");
        try (PklHelper pkl = new PklHelper(null)) {
            assertEquals(
                    List.of("ubuntu:24.04"),
                    new DistributionHelper(pkl).getDistributionConfig(distribution).getPlatforms());
        }
    }

    @Test
    void rejectsMultipleDistributionBuildPlatforms() throws Exception {
        Path distribution = resourcePath("distribution-platforms/multiple-platforms.pkl");
        try (PklHelper pkl = new PklHelper(null)) {
            assertThrows(
                    RuntimeException.class,
                    () -> new DistributionHelper(pkl).getDistributionConfig(distribution));
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
