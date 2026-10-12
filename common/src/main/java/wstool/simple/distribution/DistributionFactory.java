package wstool.simple.distribution;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.List;
import wstool.Distribution;
import wstool.simple.PklHelper;
import wstool.simple.model.PackageMajorVersion;

@Singleton
public class DistributionFactory {
    private static final String CONFIG = "distribution.pkl";

    private final PklHelper pklHelper;

    @Inject
    public DistributionFactory(PklHelper pklHelper) {
        this.pklHelper = pklHelper;
    }

    public AnyDistribution open(Path distribution) {
        Distribution config =
                pklHelper.evaluate(distribution.resolve(CONFIG)).as(Distribution.class);

        List<PackageMajorVersion> packages =
                config.getPackages().stream()
                        .map(pkg -> new PackageMajorVersion(pkg.getName(), pkg.getVersion()))
                        .toList();

        return new LocalDistribution(config.getName(), packages, config.getPlatforms());
    }
}
