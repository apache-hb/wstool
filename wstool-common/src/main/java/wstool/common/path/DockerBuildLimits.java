package wstool.common.path;

import java.util.Objects;

public record DockerBuildLimits(long memoryBytes, String diskSize, String tmpfsSize) {
    private static final long DEFAULT_MEMORY_BYTES = 768L * 1024 * 1024;

    public DockerBuildLimits {
        if (memoryBytes <= 0) {
            throw new IllegalArgumentException("Docker build memory limit must be positive");
        }
        if (Objects.requireNonNull(diskSize).isBlank()) {
            throw new IllegalArgumentException("Docker build disk size must not be blank");
        }
        if (Objects.requireNonNull(tmpfsSize).isBlank()) {
            throw new IllegalArgumentException("Docker build tmpfs size must not be blank");
        }
    }

    public static DockerBuildLimits defaults() {
        return new DockerBuildLimits(DEFAULT_MEMORY_BYTES, "2G", "512m");
    }
}
