package wstool.common.impl;

import java.nio.file.Path;
import wstool.common.CodePackage;
import wstool.common.LocalStorage;
import wstool.common.S3ArtifactStore;
import wstool.common.Storage;
import wstool.common.model.PackageVersion;

public class StorageImpl implements Storage, AutoCloseable {
    private final Path cache;
    private final LocalStorage localStorage;
    private final S3ArtifactStore artifacts;

    public StorageImpl(Path cache, LocalStorage localStorage) {
        this.cache = cache;
        this.localStorage = localStorage;
        this.artifacts = new S3ArtifactStore();
    }

    @Override
    public CodePackage fetch(PackageVersion version) {
        try {
            return localStorage.get(version);
        } catch (IllegalStateException e) {
            artifacts.fetch(version, cache);
            return localStorage.get(version);
        }
    }

    @Override
    public void close() throws Exception {
        artifacts.close();
    }
}
