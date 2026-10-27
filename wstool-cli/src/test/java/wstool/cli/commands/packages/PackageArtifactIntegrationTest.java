package wstool.cli.commands.packages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import wstool.cli.commands.CliHelper;
import wstool.test.components.CliResult;
import wstool.test.components.PackageGitManager;
import wstool.test.components.StorageServerManager;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class PackageArtifactIntegrationTest {
    @Container private static final GiteaContainer GITEA = PackageGitManager.container();
    @Container private static final GenericContainer<?> GARAGE = StorageServerManager.container();

    private Path tmp;
    private PackageGitManager git;
    private CliHelper cli;
    private StorageServerManager storage;
    private S3Client s3;
    private String bucket;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        tmp =
                Path.of(
                                "build",
                                "package-artifacts",
                                testInfo.getTestMethod().orElseThrow().getName())
                        .toAbsolutePath();
        if (Files.exists(tmp)) {
            PathUtils.deleteDirectory(tmp);
        }
        Files.createDirectories(tmp);
        git = new PackageGitManager(tmp, GITEA);
        cli = new CliHelper(tmp);
        storage = new StorageServerManager(GARAGE);
        storage.setup();
        bucket = "wstool-artifacts-" + UUID.randomUUID().toString().replace("-", "");
        s3 = storage.createBucket(bucket);
    }

    @AfterEach
    void tearDown() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Test
    void storesPackageArtifactsAndPullsMissingBuildDependencies() throws Exception {
        URI remoteRepository =
                createRepository(
                        "remote-tool",
                        CliHelper.resourceDirectory("/package-artifacts/packages/remote-tool"));
        URI consumerRepository =
                createRepository(
                        "artifact-consumer",
                        CliHelper.resourceDirectory(
                                "/package-artifacts/packages/artifact-consumer"));
        URI distributionRepository =
                createRepository(
                        "distribution",
                        CliHelper.resourceDirectory("/package-artifacts/distribution"));
        Map<String, String> environment = new HashMap<>(storage.credentialsEnvironment());
        environment.put("AWS_S3_ENDPOINT", storage.endpoint().toString());
        environment.put("ARTIFACT_BUCKET", bucket);
        environment.put("ARTIFACT_REMOTE_REPOSITORY", remoteRepository.toString());
        environment.put("ARTIFACT_CONSUMER_REPOSITORY", consumerRepository.toString());

        Path producerWorkspace =
                createWorkspace("producer", distributionRepository, environment);
        addPackage(producerWorkspace, "remote-tool", environment);
        addPackage(producerWorkspace, "artifact-consumer", environment);

        CliHelper.assertSuccess(
                cli.runCli(
                        producerWorkspace.resolve("src/remote-tool"),
                        environment,
                        "package",
                        "run",
                        "release"));
        CliHelper.assertSuccess(
                cli.runCli(
                        producerWorkspace.resolve("src/remote-tool"),
                        environment,
                        "package",
                        "store"));

        s3.headObject(
                HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key("artifacts/remote-tool/1.0.0.tar.gz")
                        .build());

        Path consumerWorkspace =
                createWorkspace("consumer", distributionRepository, environment);
        addPackage(consumerWorkspace, "artifact-consumer", environment);
        Path cachedArtifact =
                consumerWorkspace.resolve(
                        "private/cache/artifacts/remote-tool/1.0.0/package.tar.gz");
        Files.createDirectories(cachedArtifact.getParent());
        Files.writeString(cachedArtifact, "stale cached artifact");
        CliResult buildResult =
                cli.runCli(
                        consumerWorkspace.resolve("src/artifact-consumer"),
                        environment,
                        "package",
                        "run",
                        "verify");

        CliHelper.assertSuccess(buildResult);
        assertEquals(
                "remote artifact used: verify\n",
                Files.readString(
                        consumerWorkspace.resolve(
                                "private/prefix/artifact-consumer/1.0.0/remote-tool.txt")));
        assertTrue(
                Files.isRegularFile(
                        consumerWorkspace.resolve(
                                "private/prefix/remote-tool/1.0.0/bin/remote-tool")));
        assertFalse(Files.exists(consumerWorkspace.resolve("src/remote-tool")));
    }

    private Path createWorkspace(
            String name, URI distributionRepository, Map<String, String> environment)
            throws Exception {
        CliResult result =
                cli.runCli(
                        tmp,
                        environment,
                        "workspace",
                        "create",
                        "--name",
                        name,
                        "--distribution",
                        distributionRepository.toString());
        CliHelper.assertSuccess(result);
        return tmp.resolve(name);
    }

    private void addPackage(
            Path workspace, String packageName, Map<String, String> environment) throws Exception {
        CliHelper.assertSuccess(
                cli.runCli(workspace, environment, "workspace", "add", "--package", packageName));
    }

    private URI createRepository(String name, Path contents) throws Exception {
        String repository = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI uri = git.create(repository);
        git.uploadFiles(repository, contents);
        return uri;
    }
}
