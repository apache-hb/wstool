package wstool.common.packages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import wstool.Package;
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

    public PackageConfig open(Path path) throws Exception {
        Package pkg = pklHelper.evaluate(path.resolve(CONFIG)).as(Package.class);
        PackageMajorVersion name = new PackageMajorVersion(pkg.getPackage().getName(), pkg.getPackage().getVersion());
        String buildTool = pkg.getBuild().getTool();

        Map<DependencyType, List<PackageMajorVersion>> dependencies = Map.of(
                DependencyType.BUILD, mapDependencies(pkg.getBuildDependencies()),
                DependencyType.RUNTIME, mapDependencies(pkg.getRuntimeDependencies()),
                DependencyType.PUBLIC, mapDependencies(pkg.getDependencies()),
                DependencyType.TEST, mapDependencies(pkg.getTestDependencies()),
                DependencyType.OVERRIDE, mapDependencies(pkg.getOverrideDependencies())
        );

        return ImmutablePackageConfig.builder()
            .name(name)
            .buildTool(buildTool)
            .dependencies(dependencies)
            .build();
    }

    private List<PackageMajorVersion> mapDependencies(Map<String, String> dependencies) {
        List<PackageMajorVersion> result = new ArrayList<>();
        for (var entry : dependencies.entrySet()) {
            result.add(new PackageMajorVersion(entry.getKey(), entry.getValue()));
        }
        return result;
    }
}
