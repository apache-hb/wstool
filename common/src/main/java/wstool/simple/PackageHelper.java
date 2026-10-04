package wstool.simple;

import java.nio.file.Path;
import wstool.Package;

public class PackageHelper {
    private final PklHelper pkl;

    public PackageHelper(PklHelper pkl) {
        this.pkl = pkl;
    }

    public Package getPackageConfig(Path config) {
        return pkl.evaluate(config).as(Package.class);
    }
}
