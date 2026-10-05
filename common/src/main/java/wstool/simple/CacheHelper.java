package wstool.simple;

import java.nio.file.Path;
import software.amazon.awssdk.services.s3.S3Client;
import wstool.simple.archive.CreateArchiveInput;
import wstool.simple.archive.ImmutableCreateArchiveInput;
import wstool.simple.archive.ImmutableExtractArchiveInput;

public class CacheHelper {
    private final S3Client s3;
    private final ArchiveHelper archive;

    public CacheHelper(S3Client s3, ArchiveHelper archive) {
        this.s3 = s3;
        this.archive = archive;
    }

    public void upload(String bucket, String key, Path file) {
        s3.putObject(builder -> builder.bucket(bucket).key(key).build(), file);
    }

    public void download(String bucket, String key, Path file) {
        s3.getObject(builder -> builder.bucket(bucket).key(key).build(), file);
    }

    public void uploadFolder(String bucket, String key, Path folder) throws Exception {
        Path archiveFile = folder.resolveSibling(folder.getFileName() + ".zip");

        CreateArchiveInput input =
                ImmutableCreateArchiveInput.builder()
                        .source(folder)
                        .destination(archiveFile)
                        .build();

        archive.create(input);
        upload(bucket, key, archiveFile);
    }

    public void downloadFolder(String bucket, String key, Path folder) throws Exception {
        Path archiveFile = folder.resolveSibling(folder.getFileName() + ".zip");

        download(bucket, key, archiveFile);

        archive.extract(
                ImmutableExtractArchiveInput.builder()
                        .archive(archiveFile)
                        .destination(folder)
                        .build());
    }
}
