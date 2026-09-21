package wstool.common.impl;

import java.nio.file.Path;
import java.util.Objects;
import wstool.common.CodePackage;
import wstool.common.model.PackageVersion;

public class CachedPackageImpl implements CodePackage {
    private final Path artifacts;
    private final Path source;
    private final PackageVersion version;

    public CachedPackageImpl(Path artifacts, Path source, PackageVersion version) {
        this.artifacts = Objects.requireNonNull(artifacts);
        this.source = Objects.requireNonNull(source);
        this.version = Objects.requireNonNull(version);
    }

    @Override
    public PackageVersion nameAndVersion() {
        return version;
    }

    @Override
    public Path cachedArtifactLocation() {
        return artifacts;
    }

    @Override
    public Path cachedSourceLocation() {
        return source;
    }
}
