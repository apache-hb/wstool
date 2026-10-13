package wstool.common.model;

import java.util.Objects;

public record PackageMajorVersion(String name, String version) {
    public PackageMajorVersion {
        Objects.requireNonNull(name);
        Objects.requireNonNull(version);
    }
}
