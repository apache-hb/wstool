package wstool.common.model;

import java.util.Map;

public record BuildOptions(
        String tool,
        Map<String, String> buildEnvironment,
        Map<String, String> buildConfig,
        String networkIsolation,
        String sourceMount,
        String buildImage,
        String dockerImage) {

    public String effectiveDockerImage() {
        return buildImage.equals("declared") ? "scratch" : dockerImage;
    }
}
