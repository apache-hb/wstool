package wstool.npm.metadata.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.fory.json.ForyJson;
import wstool.npm.metadata.NpmPackageLock;
import wstool.npm.metadata.NpmPackageLockReader;

/** Reads npm package locks by mapping JSON directly to the typed model. */
public final class ForyNpmPackageLockReader implements NpmPackageLockReader {
    private final ForyJson json;

    public ForyNpmPackageLockReader(ForyJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public NpmPackageLock read(InputStream input) {
        try {
            return json.fromJson(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), NpmPackageLock.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
