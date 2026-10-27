package wstool.test.components;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

public class StorageServerManager {
    public static GenericContainer<?> container() {
        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse("dxflrs/garage:v2.4.1"));
        container.withExposedPorts(3900);
        container.withTmpFs(Map.of("/meta", "rw", "/data", "rw"));
        container.withCopyToContainer(
                MountableFile.forClasspathResource("garage.toml"), "/etc/garage.toml");
        container.withCommand("/garage", "-c", "/etc/garage.toml", "server");
        container.waitingFor(Wait.forLogMessage(".*S3 API server listening.*\\n", 1));
        container.withStartupTimeout(Duration.ofMinutes(2));
        return container;
    }

    private final GenericContainer<?> garage;
    private String accessKey;
    private String secretKey;

    public StorageServerManager(GenericContainer<?> garage) {
        this.garage = garage;
    }

    public void setup() throws Exception {
        String nodeId = run("node", "id").lines().findFirst().orElseThrow().split("@", 2)[0];
        run("layout", "assign", "-z", "test-zone", "-c", "1G", nodeId);
        run("layout", "apply", "--version", "1");
    }

    public S3Client createBucket(String name) throws Exception {
        accessKey = randomString(20, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        secretKey =
                randomString(40, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");

        run("key", "import", "--yes", "-n", "cli-toolchain", accessKey, secretKey);
        run("bucket", "create", name);
        run("bucket", "allow", "--read", "--write", "--owner", "--key", accessKey, name);

        AwsCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));

        S3Configuration configuration =
                S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build();

        return S3Client.builder()
                .endpointOverride(endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration)
                .build();
    }

    public Map<String, String> credentialsEnvironment() {
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException("Create a Garage bucket before requesting credentials");
        }
        return Map.of(
                "AWS_ACCESS_KEY_ID", accessKey,
                "AWS_SECRET_ACCESS_KEY", secretKey,
                "AWS_REGION", "us-east-1");
    }

    public URI endpoint() {
        return URI.create("http://" + garage.getHost() + ":" + garage.getMappedPort(3900));
    }

    private String run(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("/garage");
        command.add("-c");
        command.add("/etc/garage.toml");
        command.addAll(List.of(args));
        var result = garage.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new IOException(
                    "Garage command failed: " + result.getStdout() + result.getStderr());
        }
        return result.getStdout();
    }

    private static String randomString(int length, String chars) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }
}
