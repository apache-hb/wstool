package wstool.simple.model;

import java.util.Objects;

public record PackageBuildRevision(PackageMajorVersion majorVersion, String buildRevision) {
    public PackageBuildRevision {
        Objects.requireNonNull(majorVersion);
        Objects.requireNonNull(buildRevision);
    }
}
