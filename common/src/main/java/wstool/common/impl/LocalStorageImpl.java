package wstool.common.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import wstool.common.CodePackage;
import wstool.common.LocalStorage;
import wstool.common.model.PackageVersion;

public class LocalStorageImpl implements LocalStorage {
    private final Path cache;

    public LocalStorageImpl(Path cache) {
        this.cache = Objects.requireNonNull(cache);
    }

    @Override
    public CodePackage get(PackageVersion version) {
        Path result = cache.resolve(version.name()).resolve(version.version());

        if (!Files.exists(result)) {
            throw new IllegalStateException(
                    "Package %s not found at %s".formatted(version, result));
        }

        return new CachedPackageImpl(
                result.resolve("artifacts"), result.resolve("source"), version);
    }
}
