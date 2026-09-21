package wstool.common;

import java.nio.file.Path;

import wstool.common.model.PackageVersion;

public interface CodePackage {
    PackageVersion nameAndVersion();
    Path cachedArtifactLocation();
    Path cachedSourceLocation();
}
