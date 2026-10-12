package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import wstool.test.components.StorageServerManager;

@Testcontainers(disabledWithoutDocker = true)
public class CacheHelperTest {
    private static final String BUCKET = "cache-helper-test";

    @Container
    private static final GenericContainer<?> GARAGE = StorageServerManager.container();
    private static final StorageServerManager STORAGE =
            new StorageServerManager(GARAGE);

    @TempDir Path tempDir;

    @AutoClose
    private static S3Client s3;
    private CacheHelper cache;

    @BeforeAll
    static void initializeStorageServer() throws Exception {
        STORAGE.setup();
        s3 = STORAGE.createBucket(BUCKET);
    }

    @BeforeEach
    void createCache() {
        cache = new CacheHelper(s3, new ArchiveHelper());
    }

    @Test
    void uploadsAndDownloadsFile() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Path downloaded = tempDir.resolve("downloaded.txt");
        copyResource("cache-helper/source.txt", source);

        cache.upload(BUCKET, "objects/source.txt", source);
        cache.download(BUCKET, "objects/source.txt", downloaded);

        assertEquals(Files.readString(source), Files.readString(downloaded));
    }

    @Test
    void uploadsAndDownloadsFolderAsArchive() throws Exception {
        Path source = tempDir.resolve("source-folder");
        Files.createDirectories(source.resolve("nested"));
        copyResource("cache-helper/source-folder/root.txt", source.resolve("root.txt"));
        copyResource(
                "cache-helper/source-folder/nested/child.txt", source.resolve("nested/child.txt"));
        Path destination = tempDir.resolve("restored-folder");

        cache.uploadFolder(BUCKET, "folders/source-folder.zip", source);
        cache.downloadFolder(BUCKET, "folders/source-folder.zip", destination);

        assertTrue(Files.isDirectory(destination));
        assertEquals("root file\n", Files.readString(destination.resolve("root.txt")));
        assertEquals("nested file\n", Files.readString(destination.resolve("nested/child.txt")));
    }

    private void copyResource(String name, Path destination) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + name);
            }
            Files.copy(input, destination);
        }
    }
}
