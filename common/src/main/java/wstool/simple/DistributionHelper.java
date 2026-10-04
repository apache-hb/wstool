package wstool.simple;

import java.nio.file.Path;
import wstool.Distribution;

public class DistributionHelper {
    private final PklHelper pkl;

    public DistributionHelper(PklHelper pkl) {
        this.pkl = pkl;
    }

    public Distribution getDistributionConfig(Path distribution) {
        return pkl.evaluate(distribution).as(Distribution.class);
    }
}
