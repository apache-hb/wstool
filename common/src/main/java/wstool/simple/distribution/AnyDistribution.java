package wstool.simple.distribution;

import java.util.List;

import wstool.simple.model.PackageMajorVersion;

public interface AnyDistribution {
    String name();

    List<PackageMajorVersion> packages();

    List<String> platforms();
}
