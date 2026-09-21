package wstool.common;

import wstool.common.model.PackageVersion;

public interface Storage {
    CodePackage fetch(PackageVersion version);
}
