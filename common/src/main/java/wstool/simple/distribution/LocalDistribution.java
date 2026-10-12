package wstool.simple.distribution;

import java.util.List;
import wstool.simple.model.PackageMajorVersion;

public record LocalDistribution(
        String name, List<PackageMajorVersion> packages, List<String> platforms)
        implements AnyDistribution {}
