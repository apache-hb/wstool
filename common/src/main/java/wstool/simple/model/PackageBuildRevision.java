package wstool.simple.model;

import java.util.Objects;

public record PackageBuildRevision(PackageMajorVersion majorVersion, String revision) {
    public PackageBuildRevision {
        Objects.requireNonNull(majorVersion);
        Objects.requireNonNull(revision);
    }
}
