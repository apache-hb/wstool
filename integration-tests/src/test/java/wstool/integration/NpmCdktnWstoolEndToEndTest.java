package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import wstool.npm.importer.CachedNpmProxy;
import wstool.test.components.PackageGitManager;
import wstool.test.components.StorageServerManager;

/** Exercises npm import and CDKTN synthesis through the published command-line interfaces. */
@Testcontainers(disabledWithoutDocker = true)
class NpmCdktnWstoolEndToEndTest {
    private static final String BUILD_IMAGE = "wstool-npm-integration:latest";
    private static final String ARTIFACT_DIRECTORY = "build/integration-cdktn-s3-e2e";

    @Container private static final GenericContainer<?> GARAGE = StorageServerManager.container();
    private static final StorageServerManager STORAGE = new StorageServerManager(GARAGE);
    private static S3Client storageClient;

    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    private Path artifacts;
    private PackageGitManager gitManager;
    private URI currentProjectRepository;

    @BeforeEach
    void prepareInspectableArtifacts() throws Exception {
        artifacts = Path.of(ARTIFACT_DIRECTORY).toAbsolutePath();
        gitManager = new PackageGitManager(artifacts, GITEA);
        resetDirectory(artifacts);
        Files.createDirectories(artifacts);
    }

    @BeforeAll
    static void initializeStorageServer() throws Exception {
        STORAGE.setup();
        storageClient = STORAGE.createBucket("wstool-npm-integration");
    }

    @AfterAll
    static void closeStorageClient() {
        storageClient.close();
    }

    @Test
    void importsCdktnAndSynthesizesS3BucketLifecycleRules() throws Exception {
        new ImageFromDockerfile(BUILD_IMAGE, false)
                .withFileFromPath("Dockerfile", resourcePath("npm/Dockerfile"))
                .get();

        Path imported = artifacts.resolve("npm-import");
        Path proxyCache = artifacts.resolve("npm-registry-cache");
        Path npmCache = artifacts.resolve("npm-local-cache");
        try (var proxy = new CachedNpmProxy(proxyCache)) {
            runImporter(imported, npmCache, proxy.url(), "cdktn@0.24.0", "constructs@10.6.0");

            Path appRepository = artifacts.resolve("project");
            copyTree(resourcePath("npm-cdktn/project"), appRepository);
            Files.copy(
                    imported.resolve("npm-dependencies.pkl"),
                    appRepository.resolve("npm-dependencies.pkl"));
            currentProjectRepository = uploadRepository("cdktn-project", appRepository);

            Path distribution = artifacts.resolve("distribution");
            Files.createDirectories(distribution);
            Files.copy(imported.resolve("packages.pkl"), distribution.resolve("packages.pkl"));
            Files.copy(
                    resourcePath("npm-cdktn/distribution/distribution.pkl"),
                    distribution.resolve("distribution.pkl"));
            URI distributionRepository = uploadRepository("cdktn-distribution", distribution);

            runWstool(artifacts, "workspace", "create", "--name", "cdktn-s3");
            Path workspace = artifacts.resolve("cdktn-s3");
            runWstool(
                    workspace,
                    "workspace",
                    "setup",
                    "--distro",
                    "cdktn-s3-integration",
                    "--from",
                    distributionRepository.toString(),
                    "--branch",
                    "master");

            List<ImportedPackage> npmPackages =
                    importedNpmPackages(imported.resolve("package-tree/packages"));
            List<String> addArguments = new ArrayList<>(List.of("workspace", "add"));
            for (ImportedPackage dependency : npmPackages) {
                addArguments.add("--package");
                addArguments.add(dependency.name());
            }
            addArguments.add("--package");
            addArguments.add("wstool-npm-import");
            addArguments.add("--package");
            addArguments.add("cdktn-s3-project");
            runWstool(workspace, addArguments.toArray(String[]::new));

            runWstool(workspace, "build", "wstool-npm-import@1.0.0");
            for (ImportedPackage dependency : npmPackages) {
                runWstool(workspace, "build", dependency.name() + "@" + dependency.version());
            }
            runWstool(workspace, "build", "cdktn-s3-project@1.0.0");

            assertTrue(Files.isDirectory(workspace.resolve("src")), "workspace should contain src");
            assertTrue(
                    Files.isDirectory(workspace.resolve("private")),
                    "workspace should contain private build state");
            Path projectPrefix = workspace.resolve("private/prefix/cdktn-s3-project");
            List<Path> synthesizedConfigurations;
            try (var paths = Files.walk(projectPrefix)) {
                synthesizedConfigurations =
                        paths.filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".tf.json"))
                                .toList();
            }
            assertEquals(1, synthesizedConfigurations.size(), "CDKTN should synthesize one stack");
            String terraform = Files.readString(synthesizedConfigurations.getFirst());
            assertTrue(terraform.contains("\"aws_s3_bucket\""));
            assertTrue(terraform.contains("\"bucket\": \"cdktn-lifecycle-integration\""));
            assertTrue(terraform.contains("\"aws_s3_bucket_lifecycle_configuration\""));
            assertTrue(terraform.contains("\"expire-logs-after-30-days\""));
            assertTrue(terraform.contains("\"prefix\": \"logs/\""));
            assertTrue(terraform.contains("\"days\": 30"));
            assertTrue(terraform.contains("\"transition-to-glacier\""));
            assertTrue(terraform.contains("\"storage_class\": \"GLACIER\""));
            assertTrue(proxy.tarballRequests() > 0, "Build should fetch the external npm tarballs");
        }
    }

    private void runImporter(Path output, Path npmCache, String registry, String... packages)
            throws Exception {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                requiredEnv("WSTOOL_IMPORT_LAUNCHER"),
                                "npm",
                                "--output",
                                output.toString(),
                                "--registry",
                                registry,
                                "--cache",
                                npmCache.toString()));
        command.addAll(List.of(packages));
        runCommand(artifacts, command, "npm importer failed", Map.of());
    }

    private void runWstool(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(requiredEnv("WSTOOL_CLI_LAUNCHER"));
        command.addAll(List.of(arguments));
        Map<String, String> environment =
                Map.ofEntries(
                        Map.entry("AWS_ACCESS_KEY_ID", storageCredentials().accessKeyId()),
                        Map.entry("AWS_SECRET_ACCESS_KEY", storageCredentials().secretAccessKey()),
                        Map.entry("AWS_REGION", "us-east-1"),
                        Map.entry("AWS_S3_ENDPOINT", s3Endpoint()),
                        Map.entry("WST_BUILD_IMAGE", BUILD_IMAGE),
                        Map.entry("WSTOOL_CLI_ARCHIVE", requiredEnv("WSTOOL_CLI_ARCHIVE")),
                        Map.entry("WSTOOL_CLI_CHECKSUM", sha256(archivePath("WSTOOL_CLI_ARCHIVE"))),
                        Map.entry(
                                "WSTOOL_GRADLE_PACKAGE_URI",
                                archivePath("WSTOOL_GRADLE_PACKAGE").toUri().toString()),
                        Map.entry(
                                "WSTOOL_GRADLE_PACKAGE_CHECKSUM",
                                sha256(archivePath("WSTOOL_GRADLE_PACKAGE"))),
                        Map.entry(
                                "WSTOOL_GRADLE_RUNNER_PACKAGE_URI",
                                archivePath("WSTOOL_GRADLE_RUNNER_PACKAGE").toUri().toString()),
                        Map.entry(
                                "WSTOOL_GRADLE_RUNNER_PACKAGE_CHECKSUM",
                                sha256(archivePath("WSTOOL_GRADLE_RUNNER_PACKAGE"))),
                        Map.entry(
                                "WSTOOL_MAVEN_TOOL_PACKAGE_URI",
                                archivePath("WSTOOL_MAVEN_TOOL_PACKAGE").toUri().toString()),
                        Map.entry(
                                "WSTOOL_MAVEN_TOOL_PACKAGE_CHECKSUM",
                                sha256(archivePath("WSTOOL_MAVEN_TOOL_PACKAGE"))),
                        Map.entry(
                                "WSTOOL_NPM_IMPORT_PACKAGE_URI",
                                archivePath("WSTOOL_NPM_IMPORT_PACKAGE").toUri().toString()),
                        Map.entry(
                                "WSTOOL_NPM_IMPORT_PACKAGE_CHECKSUM",
                                sha256(archivePath("WSTOOL_NPM_IMPORT_PACKAGE"))),
                        Map.entry("WSTOOL_NPM_PROJECT_REPO", currentProjectRepository.toString()));
        runCommand(directory, command, "wstool failed", environment);
    }

    private void runCommand(
            Path directory,
            List<String> command,
            String failureMessage,
            Map<String, String> environment)
            throws Exception {
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(
                0,
                process.waitFor(),
                failureMessage + ": " + String.join(" ", command) + "\n" + output);
    }

    private URI uploadRepository(String name, Path directory) throws Exception {
        String repositoryName = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI repository = gitManager.create(repositoryName);
        gitManager.uploadFiles(repositoryName, directory);
        return repository;
    }

    private List<ImportedPackage> importedNpmPackages(Path packagesRoot) throws IOException {
        try (var paths = Files.walk(packagesRoot)) {
            return paths.filter(Files::isDirectory)
                    .filter(directory -> Files.isRegularFile(directory.resolve("wstool.pkl")))
                    .map(
                            directory -> {
                                Path relative = packagesRoot.relativize(directory);
                                Path npmName = relative.getParent();
                                String name =
                                        "npm_"
                                                + npmName.toString()
                                                        .replace(
                                                                directory
                                                                        .getFileSystem()
                                                                        .getSeparator(),
                                                                "/");
                                return new ImportedPackage(name, relative.getFileName().toString());
                            })
                    .toList();
        }
    }

    private void resetDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private static Path resourcePath(String name) throws Exception {
        var resource = NpmCdktnWstoolEndToEndTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path archivePath(String environment) {
        String value = requiredEnv(environment);
        return value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing integration test setting " + name);
        }
        return value;
    }

    private static String s3Endpoint() {
        return STORAGE.endpoint().toString();
    }

    private static software.amazon.awssdk.identity.spi.AwsCredentialsIdentity storageCredentials() {
        return storageClient
                .serviceClientConfiguration()
                .credentialsProvider()
                .resolveIdentity()
                .join();
    }

    private record ImportedPackage(String name, String version) {}
}
