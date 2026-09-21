package wstool.common.model;

import java.nio.file.Path;
import java.util.Map;
import wstool.Distribution;

public record DistributionDescriptor(Distribution value, Path worldFile) {
    public String name() {
        return value.getName();
    }

    public Distribution.Storage storage() {
        return value.getStorage();
    }

    public Map<String, ? extends Distribution.PackageSource> packages() {
        return value.getPackages();
    }
}
