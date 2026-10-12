package wstool.simple;

import java.nio.file.Path;
import java.util.Objects;

import wstool.Package;
import wstool.simple.model.PackageMajorVersion;

public record LocalPackage(PackageMajorVersion name, Path workspace, Path source, String buildTool) implements MaterializedPackage {
    public static final String CONFIG = "wstool.pkl";

    public LocalPackage {
        Objects.requireNonNull(name);
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(source);
        Objects.requireNonNull(buildTool);
    }

    public static LocalPackage ofConfig(Workspace workspace, Package config) {
        PackageMajorVersion pmv = new PackageMajorVersion(config.getPackage().getName(), config.getPackage().getVersion());
        return new LocalPackage(pmv, workspace.path(), clonedSource(workspace, pmv), config.getBuildOptions().getTool());
    }

    public static LocalPackage ofCloned(PackageMajorVersion name, Path workspace) {
        return new LocalPackage(name, workspace, workspace.resolve("src").resolve(name.name()), "");
    }

    public static LocalPackage ofCached(PackageMajorVersion name, Path workspace) {
        Path source =
                workspace
                        .resolve("private/cache/package-sources")
                        .resolve(name.name())
                        .resolve(name.version());
        return new LocalPackage(name, workspace, source, "");
    }

    public LocalPackage nested(PackageMajorVersion name, Path workspace, Path folder) {
        return new LocalPackage(name, workspace, source.resolve(folder), "");
    }

    @Override
    public Path config() {
        return source.resolve(CONFIG);
    }

    @Override
    public Path builddir() {
        return workspace
                .resolve("private/build")
                .resolve(name.name())
                .resolve(name.version());
    }

    @Override
    public Path prefix() {
        return workspace
                .resolve("private/prefix")
                .resolve(name.name())
                .resolve(name.version());
    }

    private static Path clonedSource(Workspace workspace, PackageMajorVersion pmv) {
        return workspace.path().resolve("src").resolve(pmv.name());
    }
}
