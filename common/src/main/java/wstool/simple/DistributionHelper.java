package wstool.simple;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import wstool.Distribution;
import wstool.Distribution.PackageSource;

public class DistributionHelper {
    private final PklHelper pkl;

    public DistributionHelper(PklHelper pkl) {
        this.pkl = pkl;
    }

    public Distribution getDistributionConfig(Path distribution) {
        return pkl.evaluate(distribution).get("distribution").as(Distribution.class);
    }

    public List<PackageSource> findPackageVersions(Distribution distribution, String name) {
        List<PackageSource> candidates = new ArrayList<>();
        for (PackageSource pkg : distribution.getPackages()) {
            if (pkg.getName().equals(name)) {
                candidates.add(pkg);
            }
        }
        return candidates;
    }

    public PackageSource findPackageMajorVersion(
            Distribution distribution, String name, String version) {
        List<PackageSource> candidates = findPackageVersions(distribution, name);

        if (!candidates.isEmpty()) {
            if (version != null) {
                for (PackageSource source : candidates) {
                    if (source.getVersion().equals(version)) {
                        return source;
                    }
                }
            } else if (candidates.size() == 1) {
                return candidates.getFirst();
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "Multiple versions of package '%s' found in distribution. Please specify a version.",
                                name));
            }
        }

        throw new IllegalArgumentException(
                String.format(
                        "No package with name '%s' and version '%s' found in distribution.",
                        name, version));
    }
}
