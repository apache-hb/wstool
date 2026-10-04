package wstool.cli.commands.packages;

import java.nio.file.Path;
import wstool.common.api.CodePackage;
import wstool.common.model.PackageMajorVersion;

record RunCodePackage(PackageMajorVersion version, Path source, Path prefix)
        implements CodePackage {}
