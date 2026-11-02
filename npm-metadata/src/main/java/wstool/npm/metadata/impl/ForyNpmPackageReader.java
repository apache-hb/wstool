package wstool.npm.metadata.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.fory.json.ForyJson;
import wstool.npm.metadata.NpmPackageMetadata;
import wstool.npm.metadata.NpmPackageReader;

/** Reads npm package manifests by mapping JSON directly to the typed model. */
public final class ForyNpmPackageReader implements NpmPackageReader {
    private final ForyJson json;

    public ForyNpmPackageReader(ForyJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public NpmPackageMetadata read(InputStream input) {
        try {
            return json.fromJson(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    NpmPackageMetadata.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
