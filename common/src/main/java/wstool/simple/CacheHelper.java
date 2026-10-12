package wstool.simple;

import java.io.IOException;
import java.nio.file.Files;
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
        try {
            CreateArchiveInput input =
                    ImmutableCreateArchiveInput.builder()
                            .source(folder)
                            .destination(archiveFile)
                            .build();
            archive.create(input);
            upload(bucket, key, archiveFile);
        } finally {
            Files.deleteIfExists(archiveFile);
        }
    }

    public void downloadFolder(String bucket, String key, Path folder) throws Exception {
        Path archiveFile = folder.resolveSibling(folder.getFileName() + ".zip");
        try {
            Files.createDirectories(archiveFile.getParent());
            download(bucket, key, archiveFile);
            deleteTree(folder);
            archive.extract(
                    ImmutableExtractArchiveInput.builder()
                            .archive(archiveFile)
                            .destination(folder)
                            .build());
        } finally {
            Files.deleteIfExists(archiveFile);
        }
    }

    private void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
