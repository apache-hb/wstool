package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Testcontainers(disabledWithoutDocker = true)
public class CacheHelperTest {
    private static final String BUCKET = "cache-helper-test";
    private static final String ACCESS_KEY = "GK0123456789abcdef01234567";
    private static final String SECRET_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Container
    private static final GenericContainer<?> garage =
            new GenericContainer<>(DockerImageName.parse("dxflrs/garage:v2.4.1"))
                    .withExposedPorts(3900)
                    .withTmpFs(Map.of("/meta", "rw", "/data", "rw"))
                    .withCopyToContainer(
                            MountableFile.forClasspathResource("garage/garage.toml"),
                            "/etc/garage.toml")
                    .withCommand("/garage", "-c", "/etc/garage.toml", "server")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @TempDir Path tempDir;

    private S3Client s3;
    private CacheHelper cache;

    @BeforeAll
    static void initializeGarage() throws Exception {
        String nodeId =
                runGarage("node", "id")
                        .lines()
                        .findFirst()
                        .orElseThrow()
                        .split("@", 2)[0];
        runGarage("layout", "assign", "-z", "test-zone", "-c", "1G", nodeId);
        runGarage("layout", "apply", "--version", "1");
        runGarage("key", "import", "--yes", "-n", "cache-test", ACCESS_KEY, SECRET_KEY);
        runGarage("bucket", "create", BUCKET);
        runGarage("bucket", "allow", "--read", "--write", "--owner", "--key", ACCESS_KEY, BUCKET);
    }

    @BeforeEach
    void createClient() {
        s3 =
                S3Client.builder()
                        .endpointOverride(
                                URI.create(
                                        "http://"
                                                + garage.getHost()
                                                + ":"
                                                + garage.getMappedPort(3900)))
                        .region(Region.of("garage"))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(true)
                                        .chunkedEncodingEnabled(false)
                                        .build())
                        .build();
        cache = new CacheHelper(s3, new ArchiveHelper());
    }

    @AfterEach
    void closeClient() {
        s3.close();
    }

    @Test
    void uploadsAndDownloadsFile() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Path downloaded = tempDir.resolve("downloaded.txt");
        Files.writeString(source, "cache object contents\n");

        cache.upload(BUCKET, "objects/source.txt", source);
        cache.download(BUCKET, "objects/source.txt", downloaded);

        assertEquals(Files.readString(source), Files.readString(downloaded));
    }

    @Test
    void uploadsAndDownloadsFolderAsArchive() throws Exception {
        Path source = tempDir.resolve("source-folder");
        Files.createDirectories(source.resolve("nested"));
        Files.writeString(source.resolve("root.txt"), "root file\n");
        Files.writeString(source.resolve("nested/child.txt"), "nested file\n");
        Path destination = tempDir.resolve("restored-folder");

        cache.uploadFolder(BUCKET, "folders/source-folder.zip", source);
        cache.downloadFolder(BUCKET, "folders/source-folder.zip", destination);

        assertTrue(Files.isDirectory(destination));
        assertEquals("root file\n", Files.readString(destination.resolve("root.txt")));
        assertEquals(
                "nested file\n", Files.readString(destination.resolve("nested/child.txt")));
    }

    private static String runGarage(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("/garage");
        command.add("-c");
        command.add("/etc/garage.toml");
        command.addAll(List.of(arguments));
        var result = garage.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new IOException(
                    "Garage command failed: " + result.getStdout() + result.getStderr());
        }
        return result.getStdout();
    }
}
