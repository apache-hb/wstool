package wstool.common.packages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import wstool.Package;
import wstool.Package.ArchiveSource;
import wstool.Package.CodeSource;
import wstool.Package.FileSource;
import wstool.common.model.DependencyType;
import wstool.common.model.PackageMajorVersion;
import wstool.common.pkl.PklHelper;

@Singleton
public class PackageFactory {
    private static final String CONFIG = "wstool.pkl";

    private final PklHelper pklHelper;

    @Inject
    public PackageFactory(PklHelper pklHelper) {
        this.pklHelper = pklHelper;
    }

    public PackageConfig findUp(Path cwd) throws Exception {
        Path current = cwd.toAbsolutePath();

        while (current != null && !Files.exists(current.resolve(CONFIG))) {
            current = current.getParent();
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "No package found in current directory or any parent directories.");
        }

        return open(current);
    }

    public PackageConfig open(Path path) throws Exception {
        Package pkg = pklHelper.evaluate(path.resolve(CONFIG)).as(Package.class);
        PackageMajorVersion name =
                new PackageMajorVersion(pkg.getPackage().getName(), pkg.getPackage().getVersion());
        String buildTool = pkg.getBuild().getTool();
        List<ExternalSource> externalSources = mapExternalSources(pkg.getExternalSources());

        Map<DependencyType, List<PackageMajorVersion>> dependencies =
                Map.of(
                        DependencyType.BUILD, mapDependencies(pkg.getBuildDependencies()),
                        DependencyType.RUNTIME, mapDependencies(pkg.getRuntimeDependencies()),
                        DependencyType.PUBLIC, mapDependencies(pkg.getDependencies()),
                        DependencyType.TEST, mapDependencies(pkg.getTestDependencies()),
                        DependencyType.OVERRIDE, mapDependencies(pkg.getOverrideDependencies()));

        return ImmutablePackageConfig.builder()
                .name(name)
                .buildTool(buildTool)
                .externalSources(externalSources)
                .dependencies(dependencies)
                .build();
    }

    private List<PackageMajorVersion> mapDependencies(Map<String, String> dependencies) {
        List<PackageMajorVersion> result = new ArrayList<>();
        if (dependencies == null) {
            return result;
        }
        for (var entry : dependencies.entrySet()) {
            result.add(new PackageMajorVersion(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private List<ExternalSource> mapExternalSources(Package.ImportOptions externalSources) {
        if (externalSources == null) {
            return List.of();
        }

        return externalSources.getSources().stream().map(this::mapExternalSource).toList();
    }

    private ExternalSource mapExternalSource(CodeSource source) {
        if (source instanceof ArchiveSource archive) {
            return new ArchiveSourceConfig(
                    archive.getUri(),
                    archive.getChecksum(),
                    Math.toIntExact(archive.getStripComponents()),
                    source.getPatches());
        }
        if (source instanceof FileSource file) {
            return new FileSourceConfig(
                    file.getUri(), file.getPath(), file.getChecksum(), source.getPatches());
        }
        throw new IllegalArgumentException("Unknown external source type " + source.getClass());
    }
}
