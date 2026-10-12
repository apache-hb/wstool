package wstool.simple.distribution;

import java.nio.file.Path;

import wstool.Distribution;
import wstool.simple.PklHelper;

public class DistributionFactory {
    private static final String CONFIG = "distribution.pkl";

    private final PklHelper pklHelper;

    public DistributionFactory(PklHelper pklHelper) {
        this.pklHelper = pklHelper;
    }

    public AnyDistribution open(Path distribution) {
        Distribution config = pklHelper.evaluate(distribution.resolve(CONFIG)).as(Distribution.class);

        return new LocalDistribution(config.getName(), config.getPlatforms());
    }
}
