package wstool.simple;

import java.nio.file.Path;
import java.util.Objects;
import wstool.simple.model.PackageMajorVersion;

public record LocalPackage(PackageMajorVersion version, Path workspace, Path source) {
    public LocalPackage {
        Objects.requireNonNull(version);
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(source);
    }

    public static LocalPackage ofCloned(PackageMajorVersion name, Path workspace) {
        return new LocalPackage(name, workspace, workspace.resolve("src").resolve(name.name()));
    }

    public static LocalPackage ofCached(PackageMajorVersion name, Path workspace) {
        Path source =
                workspace
                        .resolve("private/cache/package-sources/")
                        .resolve(name.name())
                        .resolve(name.version());
        return new LocalPackage(name, workspace, source);
    }

    public LocalPackage nested(PackageMajorVersion name, Path workspace, Path folder) {
        return new LocalPackage(name, workspace, source.resolve(folder));
    }

    public Path config() {
        return source.resolve("wstool.pkl");
    }

    public Path builddir() {
        return workspace
                .resolve("private/build")
                .resolve(version.name())
                .resolve(version.version());
    }

    public Path prefix() {
        return workspace
                .resolve("private/prefix")
                .resolve(version.name())
                .resolve(version.version());
    }
}
