package wstool.workspace;

import java.nio.file.Path;
import java.util.Objects;
import wstool.common.api.CodePackage;
import wstool.common.model.PackageMajorVersion;

final class WorkspaceCodePackage implements CodePackage {
    private final PackageMajorVersion version;
    private final Path source;
    private final Path prefix;

    WorkspaceCodePackage(PackageMajorVersion version, Path source, Path prefix) {
        this.version = Objects.requireNonNull(version);
        this.source = Objects.requireNonNull(source);
        this.prefix = Objects.requireNonNull(prefix);
    }

    @Override
    public PackageMajorVersion version() {
        return version;
    }

    @Override
    public Path source() {
        return source;
    }

    @Override
    public Path prefix() {
        return prefix;
    }
}
