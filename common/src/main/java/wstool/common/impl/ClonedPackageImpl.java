package wstool.common.impl;

import java.nio.file.Path;
import java.util.Objects;

import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.core.ModuleSource;

import wstool.common.CodePackage;
import wstool.common.model.PackageVersion;

import wstool.Package;
import wstool.Package.WsToolPackage;

public class ClonedPackageImpl implements CodePackage {
    private final Path path;
    private final Package packageInfo;

    public ClonedPackageImpl(Path path) {
        this.path = Objects.requireNonNull(path);

        try (var evaluator = ConfigEvaluator.preconfigured()) {
            Config config = evaluator.evaluate(ModuleSource.path(path.resolve("Package.pkl")));
            this.packageInfo = config.as(Package.class);
        }
    }

    @Override
    public PackageVersion nameAndVersion() {
        WsToolPackage info = packageInfo.getPackage();
        return new PackageVersion(info.getName(), info.getVersion());
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
