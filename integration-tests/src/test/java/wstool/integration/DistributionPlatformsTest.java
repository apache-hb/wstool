package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import wstool.simple.DistributionHelper;
import wstool.simple.PklHelper;

class DistributionPlatformsTest {
    @ParameterizedTest
    @MethodSource("distributionPlatformCases")
    void validatesDistributionBuildPlatforms(
            String resource, List<String> expectedPlatforms, boolean shouldReject)
            throws Exception {
        Path distribution = resourcePath(resource);
        try (PklHelper pkl = new PklHelper(null)) {
            if (shouldReject) {
                assertThrows(
                        RuntimeException.class,
                        () -> new DistributionHelper(pkl).getDistributionConfig(distribution));
            } else {
                assertEquals(
                        expectedPlatforms,
                        new DistributionHelper(pkl)
                                .getDistributionConfig(distribution)
                                .getPlatforms());
            }
        }
    }

    static Stream<Arguments> distributionPlatformCases() {
        return Stream.of(
                Arguments.of(
                        "distribution-platforms/single-platform.pkl",
                        List.of("ubuntu:24.04"),
                        false),
                Arguments.of("distribution-platforms/multiple-platforms.pkl", List.of(), true));
    }

    private Path resourcePath(String name) throws Exception {
        var resource = getClass().getClassLoader().getResource(name);
        if (resource == null) {
            throw new IllegalArgumentException("Missing test resource: " + name);
        }
        return Path.of(resource.toURI());
    }
}
