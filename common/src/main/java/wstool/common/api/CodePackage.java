package wstool.common.api;

import java.nio.file.Path;
import wstool.common.model.PackageMajorVersion;

public interface CodePackage {
    PackageMajorVersion version();

    /**
     * Either a path to the package source folder in the local workspace, or a path to its source in
     * the package cache
     */
    Path source();

    /** The path to the build artifacts of this package */
    Path prefix();
}
