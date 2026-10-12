package wstool.simple;

import java.nio.file.Path;
import wstool.simple.model.PackageMajorVersion;

/** A package that exists in some way, either in the cache or cloned to the workspace */
public interface MaterializedPackage {
    PackageMajorVersion name();

    Path config();

    Path source();

    Path builddir();

    Path prefix();
}
