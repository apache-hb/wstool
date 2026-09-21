package wstool.common;

import java.nio.file.Path;
import wstool.common.model.PackageVersion;

public interface ArtifactStore extends AutoCloseable {
    void publish(PackageVersion version, Path directory);

    Path fetch(PackageVersion version, Path cacheDirectory);

    @Override
    default void close() {}
}
