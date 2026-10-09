package wstool.simple;

import java.nio.file.Path;
import wstool.simple.model.PackageMajorVersion;

/** Stores compressed installed prefixes under stable package-version object keys. */
public class PackageArtifactCache implements S3ArtifactCache {
    private final CacheHelper cache;
    private final String bucket;
    private final String namespace;

    public PackageArtifactCache(CacheHelper cache, String bucket, String namespace) {
        this.cache = cache;
        this.bucket = bucket;
        this.namespace = namespace;
    }

    @Override
    public void download(PackageMajorVersion packageVersion, Path prefix) throws Exception {
        cache.downloadFolder(bucket, key(packageVersion), prefix);
    }

    @Override
    public void upload(PackageMajorVersion packageVersion, Path prefix) throws Exception {
        cache.uploadFolder(bucket, key(packageVersion), prefix);
    }

    private String key(PackageMajorVersion packageVersion) {
        return namespace
                + "/"
                + packageVersion.name()
                + "/"
                + packageVersion.version()
                + "/prefix.zip";
    }
}
