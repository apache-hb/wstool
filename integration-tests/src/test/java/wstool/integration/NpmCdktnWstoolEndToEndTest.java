package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import wstool.npm.importer.CachedNpmProxy;

/** Exercises npm import and CDKTN synthesis through the published command-line interfaces. */
@Testcontainers(disabledWithoutDocker = true)
class NpmCdktnWstoolEndToEndTest {
    private static final String ACCESS_KEY = "GK0123456789abcdef01234567";
    private static final String SECRET_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String BUILD_IMAGE = "wstool-npm-integration:latest";
    private static final String ARTIFACT_DIRECTORY = "build/integration-cdktn-s3-e2e";

    @Container
    private static final GenericContainer<?> garage =
            new GenericContainer<>(DockerImageName.parse("dxflrs/garage:v2.4.1"))
                    .withExposedPorts(3900)
                    .withTmpFs(Map.of("/meta", "rw", "/data", "rw"))
                    .withCopyToContainer(
                            MountableFile.forClasspathResource("garage.toml"), "/etc/garage.toml")
                    .withCommand("/garage", "-c", "/etc/garage.toml", "server")
                    .waitingFor(Wait.forLogMessage(".*S3 API server listening.*\\n", 1));

    private Path artifacts;
    private String originalUserHome;

    @BeforeEach
    void prepareInspectableArtifactsAndPklCache() throws Exception {
        originalUserHome = System.getProperty("user.home");
        artifacts = Path.of(ARTIFACT_DIRECTORY).toAbsolutePath();
        resetDirectory(artifacts);
        Files.createDirectories(artifacts);

        Path pklHome = artifacts.resolve("pkl-home");
        Path packageCache =
                pklHome.resolve(".pkl/cache/package-2/pkl.konna.cloud/pkl/wstool@1.0.2");
        Files.createDirectories(packageCache);
        Path generatedPackage = Path.of("../config/build/generated/pkl/packages");
        Files.copy(
                generatedPackage.resolve("wstool@1.0.2"),
                packageCache.resolve("wstool@1.0.2.json"));
        Files.copy(
                generatedPackage.resolve("wstool@1.0.2.zip"),
                packageCache.resolve("wstool@1.0.2.zip"));
        System.setProperty("user.home", pklHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @BeforeAll
    static void initializeGarage() throws Exception {
        String nodeId =
                garageCommand("node", "id").lines().findFirst().orElseThrow().split("@", 2)[0];
        garageCommand("layout", "assign", "-z", "test-zone", "-c", "1G", nodeId);
        garageCommand("layout", "apply", "--version", "1");
        garageCommand("key", "import", "--yes", "-n", "wstool-cdktn", ACCESS_KEY, SECRET_KEY);
        garageCommand("bucket", "create", "wstool-npm-integration");
        garageCommand(
                "bucket",
                "allow",
                "--read",
                "--write",
                "--owner",
                "--key",
                ACCESS_KEY,
                "wstool-npm-integration");
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
            initializeGitRepository(appRepository);

            Path distribution = artifacts.resolve("distribution");
            Files.createDirectories(distribution);
            Files.copy(imported.resolve("packages.pkl"), distribution.resolve("packages.pkl"));
            Files.copy(
                    resourcePath("npm-cdktn/distribution/world.pkl"),
                    distribution.resolve("world.pkl"));
            initializeGitRepository(distribution);

            runWstool(artifacts, "workspace", "create", "--name", "cdktn-s3");
            Path workspace = artifacts.resolve("cdktn-s3");
            runWstool(
                    workspace,
                    "workspace",
                    "setup",
                    "--distro",
                    "cdktn-s3-integration",
                    "--from",
                    distribution.toString(),
                    "--branch",
                    "main");

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
        runCommand(
                artifacts,
                command,
                "npm importer failed",
                Map.of(
                        "HOME",
                        artifacts.resolve("pkl-home").toString(),
                        "JAVA_TOOL_OPTIONS",
                        "-Duser.home=" + artifacts.resolve("pkl-home")));
    }

    private void runWstool(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(requiredEnv("WSTOOL_CLI_LAUNCHER"));
        command.addAll(List.of(arguments));
        Map<String, String> environment =
                Map.ofEntries(
                        Map.entry("HOME", artifacts.resolve("pkl-home").toString()),
                        Map.entry(
                                "JAVA_TOOL_OPTIONS",
                                "-Duser.home=" + artifacts.resolve("pkl-home")),
                        Map.entry("AWS_ACCESS_KEY_ID", ACCESS_KEY),
                        Map.entry("AWS_SECRET_ACCESS_KEY", SECRET_KEY),
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
                        Map.entry(
                                "WSTOOL_NPM_PROJECT_REPO",
                                artifacts.resolve("project").toUri().toString()));
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

    private void initializeGitRepository(Path repository) throws Exception {
        runCommand(
                repository,
                List.of("git", "init", "--initial-branch=main"),
                "git init failed",
                Map.of());
        runCommand(
                repository,
                List.of("git", "config", "user.name", "wstool integration test"),
                "git config failed",
                Map.of());
        runCommand(
                repository,
                List.of("git", "config", "user.email", "wstool-test@example.invalid"),
                "git config failed",
                Map.of());
        runCommand(repository, List.of("git", "add", "."), "git add failed", Map.of());
        runCommand(
                repository,
                List.of("git", "commit", "--message", "CDKTN npm integration fixture"),
                "git commit failed",
                Map.of());
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
        return "http://" + garage.getHost() + ":" + garage.getMappedPort(3900);
    }

    private static String garageCommand(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("/garage", "-c", "/etc/garage.toml"));
        command.addAll(List.of(arguments));
        var result = garage.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new IOException(
                    "Garage command failed: " + result.getStdout() + result.getStderr());
        }
        return result.getStdout();
    }

    private record ImportedPackage(String name, String version) {}
}
