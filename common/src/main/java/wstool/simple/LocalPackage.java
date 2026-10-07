package wstool.simple;

import java.nio.file.Path;
import wstool.simple.model.PackageMajorVersion;

public record LocalPackage(PackageMajorVersion version, Path source, Path prefix) {}
