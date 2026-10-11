package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.simple.DistributionHelper;
import wstool.simple.PklHelper;

class DistributionPlatformsTest {
    @TempDir Path tempDir;

    @Test
    void acceptsOneDistributionBuildPlatform() throws Exception {
        Path distribution = distributionWithPlatforms("\"ubuntu:24.04\"");
        try (PklHelper pkl = new PklHelper(null)) {
            assertEquals(
                    List.of("ubuntu:24.04"),
                    new DistributionHelper(pkl).getDistributionConfig(distribution).getPlatforms());
        }
    }

    @Test
    void rejectsMultipleDistributionBuildPlatforms() throws Exception {
        Path distribution = distributionWithPlatforms("\"ubuntu:24.04\"; \"alpine:3.21\"");
        try (PklHelper pkl = new PklHelper(null)) {
            assertThrows(
                    RuntimeException.class,
                    () -> new DistributionHelper(pkl).getDistributionConfig(distribution));
        }
    }

    private Path distributionWithPlatforms(String platforms) throws Exception {
        Path schema =
                Path.of(System.getProperty("user.dir"))
                        .getParent()
                        .resolve("config/src/main/resources/distribution.pkl");
        Path distribution = tempDir.resolve("distribution.pkl");
        Files.writeString(
                distribution,
                "amends \""
                        + schema.toUri()
                        + "\"\n"
                        + "name = \"platform-test\"\n"
                        + "storage { bucket = \"platform-test\" }\n"
                        + "platforms { "
                        + platforms
                        + " }\n"
                        + "packages {}\n");
        return distribution;
    }
}
