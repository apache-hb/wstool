package wstool.simple;

import java.nio.file.Path;
import wstool.simple.model.PackageMajorVersion;

public interface S3ArtifactCache {
    void download(PackageMajorVersion packageVersion, Path prefix) throws Exception;

    void upload(PackageMajorVersion packageVersion, Path prefix) throws Exception;
}
