package wstool.common.impl;

import java.nio.file.Path;
import java.util.Objects;
import wstool.common.CodePackage;
import wstool.common.PackageDescriptorReader;
import wstool.common.model.PackageVersion;

public class ClonedPackageImpl implements CodePackage {
    private final Path path;
    private final PackageVersion version;

    public ClonedPackageImpl(Path path) {
        this.path = Objects.requireNonNull(path);

        this.version = PackageDescriptorReader.read(path).version();
    }

    @Override
    public PackageVersion nameAndVersion() {
        return version;
    }

    @Override
    public Path cachedArtifactLocation() {
        return path.resolve("builddir");
    }

    @Override
    public Path cachedSourceLocation() {
        return path;
    }
}
