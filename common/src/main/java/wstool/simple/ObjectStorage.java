package wstool.simple;

import software.amazon.awssdk.services.s3.S3Client;
import wstool.Package;
import wstool.simple.model.PackageMajorVersion;

public class ObjectStorage {
    private final S3Client s3Client;
    private final String bucketName;

    public ObjectStorage(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    public void savePackageMetadata(PackageMajorVersion packageMajorVersion, Package metadata) {
        // Implementation to save package metadata to S3
    }
}
