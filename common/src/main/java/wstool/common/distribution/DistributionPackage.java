package wstool.common.distribution;

import java.net.URI;
import java.nio.file.Path;
import wstool.common.model.PackageMajorVersion;

public sealed interface DistributionPackage {
    PackageMajorVersion name();

    public record GitRepository(PackageMajorVersion name, URI repository, String branch)
            implements DistributionPackage {}

    public record Archive(PackageMajorVersion name, URI archive, String checksum)
            implements DistributionPackage {}

    public record Folder(PackageMajorVersion name, DistributionPackage source, Path path)
            implements DistributionPackage {}
}
