package wstool.common.cache;

import software.amazon.awssdk.services.s3.S3Client;

public class ObjectStorage {
    private final S3Client s3Client;
    private final String bucketName;

    public ObjectStorage(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }
}
