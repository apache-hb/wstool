package wstool.common.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import wstool.common.CodePackage;
import wstool.common.LocalStorage;
import wstool.common.Storage;
import wstool.common.model.PackageVersion;

public class StorageImpl implements Storage, AutoCloseable {
    private final Path cache;
    private final S3Client client;
    private final LocalStorage localStorage;

    public StorageImpl(Path cache, LocalStorage localStorage) {
        this.cache = cache;
        this.client = S3Client.create();
        this.localStorage = localStorage;
    }

    @Override
    public CodePackage fetch(PackageVersion version) {
        try {
            return localStorage.get(version);
        } catch (IllegalStateException e) {
            downloadArtifacts(version);
            return localStorage.get(version);
        }
    }

    private void downloadArtifacts(PackageVersion version) {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket("storage")
            .key("artifacts/%s/%s/artifact.zip".formatted(version.name(), version.version()))
            .build();

        Path root = cache.resolve(version.name()).resolve(version.version());

        try (ZipInputStream zip = new ZipInputStream(client.getObject(request))) {
            // Extract all entries into the cache on disk

            while (true) {
                var entry = zip.getNextEntry();
                if (entry == null) {
                    break;
                }

                var target = root.resolve("artifacts").resolve(entry.getName());
                if (entry.isDirectory()) {
                    target.toFile().mkdirs();
                } else {
                    target.getParent().toFile().mkdirs();
                    Files.copy(zip, target);
                }
            }

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws Exception {
        client.close();
    }
}
