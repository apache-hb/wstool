package wstool.common;

import wstool.common.model.PackageVersion;

public interface LocalStorage {
    CodePackage get(PackageVersion version);
}
