package wstool.simple;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import wstool.Distribution;
import wstool.Distribution.PackageSource;
import wstool.Package;
import wstool.simple.model.PackageMajorVersion;

@Singleton
public class PackageHelper {
    private final PklHelper pkl;

    @Inject
    public PackageHelper(PklHelper pkl) {
        this.pkl = pkl;
    }

    public Package getPackageConfig(Path config) {
        return pkl.evaluate(config).as(Package.class);
    }

    public PackageSource getPackageSource(Distribution distribution, PackageMajorVersion name) {
        return distribution.getPackages().stream()
                .filter(pkg -> pkg.getName().equals(name.name()))
                .filter(pkg -> pkg.getVersion().equals(name.version()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No package with name '"
                                                + name.name()
                                                + "' and version '"
                                                + name.version()
                                                + "' found in distribution."));
    }
}
