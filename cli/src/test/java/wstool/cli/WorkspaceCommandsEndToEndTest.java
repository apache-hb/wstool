package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import wstool.test.components.PackageGitManager;
import wstool.test.components.StorageServerManager;

/** Exercises real source builds, workspace package resolution, S3 artifacts, and target runs. */
@Testcontainers(disabledWithoutDocker = true)
class WorkspaceCommandsEndToEndTest {
    private static final String BUCKET = "cli-toolchain";
    private static final List<String> PACKAGE_NAMES =
            List.of(
                    "cmake",
                    "ninja",
                    "zlib",
                    "expat",
                    "m4",
                    "bison",
                    "flex",
                    "binutils",
                    "gmp",
                    "mpfr",
                    "mpc",
                    "isl",
                    "gcc",
                    "c-smoke");
    private static final List<String> BUILD_ORDER =
            List.of(
                    "zlib",
                    "expat",
                    "cmake",
                    "ninja",
                    "m4",
                    "bison",
                    "flex",
                    "binutils",
                    "gmp",
                    "mpfr",
                    "mpc",
                    "isl");
    private static final Map<String, String> PACKAGE_VERSIONS =
            Map.ofEntries(
                    Map.entry("cmake", "3.30.5"),
                    Map.entry("ninja", "1.12.1"),
                    Map.entry("zlib", "1.3.1"),
                    Map.entry("expat", "2.6.4"),
                    Map.entry("m4", "1.4.19"),
                    Map.entry("bison", "3.8.2"),
                    Map.entry("flex", "2.6.4"),
                    Map.entry("binutils", "2.43.1"),
                    Map.entry("gmp", "6.3.0"),
                    Map.entry("mpfr", "4.2.1"),
                    Map.entry("mpc", "1.3.1"),
                    Map.entry("isl", "0.26"),
                    Map.entry("gcc", "14.2.0"),
                    Map.entry("c-smoke", "1.0.0"));

    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    @Container private static final GenericContainer<?> GARAGE = StorageServerManager.container();
    private static final StorageServerManager STORAGE = new StorageServerManager(GARAGE);

    @AutoClose private static S3Client s3;

    @TempDir Path tempDir;

    private PackageGitManager gitManager;
    private String suffix;
    private Map<String, String> repositoryEnvironment = Map.of();
    private String buildImage;

    @BeforeAll
    static void initializeStorageServer() throws Exception {
        STORAGE.setup();
        s3 = STORAGE.createBucket(BUCKET);
    }

    @BeforeEach
    void setUp() {
        gitManager = new PackageGitManager(tempDir, GITEA);
        suffix = UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void buildsRealBootstrapDependenciesAndGccThroughWorkspaceCommands() throws Exception {
        buildImage =
                new ImageFromDockerfile("wstool-build-e2e:latest", false)
                        .withFileFromClasspath("Dockerfile", "workspace-e2e/Dockerfile")
                        .get();
        Map<String, URI> repositories = uploadPackageRepositories();
        URI distribution = uploadDistribution(repositories);

        Path parent = tempDir.resolve("workspaces");
        Files.createDirectories(parent);
        runCli(parent, "workspace", "create", "--name", "toolchain");
        Path workspace = parent.resolve("toolchain");
        runCli(
                workspace,
                "workspace",
                "setup",
                "--distro",
                "bootstrap-test",
                "--from",
                distribution.toString(),
                "--branch",
                "master");
        List<String> addArguments = new ArrayList<>(List.of("workspace", "add"));
        for (String name : BUILD_ORDER) {
            addArguments.add("--package");
            addArguments.add(name);
        }
        runCli(workspace, addArguments.toArray(String[]::new));

        Properties sources = sourceProperties();
        for (String name : BUILD_ORDER) {
            Path packageDirectory = workspace.resolve("src").resolve(name);
            downloadAndExtractSource(packageDirectory, name, sources);
        }

        for (String name : BUILD_ORDER) {
            runCli(workspace, "build", name + "@" + PACKAGE_VERSIONS.get(name));
            assertArtifactInS3(name);
        }

        assertTrue(Files.isExecutable(workspace.resolve("private/prefix/cmake/3.30.5/bin/cmake")));
        assertTrue(Files.isExecutable(workspace.resolve("private/prefix/ninja/1.12.1/bin/ninja")));
        assertTrue(!Files.exists(workspace.resolve("private/build/cmake/3.30.5/sysroot")));

        Path consumerParent = tempDir.resolve("workspaces");
        runCli(consumerParent, "workspace", "create", "--name", "consumer");
        Path consumer = consumerParent.resolve("consumer");
        runCli(
                consumer,
                "workspace",
                "setup",
                "--distro",
                "bootstrap-test",
                "--from",
                distribution.toString(),
                "--branch",
                "master");
        runCli(consumer, "workspace", "add", "--package", "gcc", "--package", "c-smoke");
        downloadAndExtractSource(consumer.resolve("src/gcc"), "gcc", sources);

        for (String dependency : List.of("binutils", "gmp", "mpfr", "mpc", "isl")) {
            assertTrue(
                    !Files.exists(consumer.resolve("src").resolve(dependency)),
                    "Consumer workspace unexpectedly contains source checkout for " + dependency);
        }

        runCli(consumer, "build", "gcc@" + PACKAGE_VERSIONS.get("gcc"));
        assertArtifactInS3("gcc");
        runCli(consumer, "build", "c-smoke@" + PACKAGE_VERSIONS.get("c-smoke"));
        assertArtifactInS3("c-smoke");

        Path gccPrefix = consumer.resolve("private/prefix/gcc/14.2.0");
        Path compiler = gccPrefix.resolve("bin/x86_64-linux-gnu-gcc");
        assertTrue(Files.isExecutable(compiler), "GCC source build did not install its compiler");
        Path smokeObject = consumer.resolve("private/prefix/c-smoke/1.0.0/program.o");
        assertTrue(Files.isRegularFile(smokeObject), "C smoke package did not install program.o");
        assertTrue(Files.size(smokeObject) > 0, "GCC produced an empty object file");

        assertTrue(Files.isRegularFile(consumer.resolve("private/prefix/gmp/6.3.0/include/gmp.h")));
        assertTrue(
                Files.isRegularFile(consumer.resolve("private/prefix/mpfr/4.2.1/include/mpfr.h")));

        assertTrue(!Files.exists(consumer.resolve("private/build/gcc/14.2.0/sysroot")));
    }

    private void assertArtifactInS3(String name) {
        assertTrue(
                s3.headObject(
                                builder ->
                                        builder.bucket(BUCKET)
                                                .key(
                                                        "bootstrap-test/"
                                                                + name
                                                                + "/"
                                                                + PACKAGE_VERSIONS.get(name)
                                                                + "/prefix.zip"))
                        != null,
                "Build artifact was not uploaded for " + name);
    }

    private Map<String, URI> uploadPackageRepositories() throws Exception {
        Map<String, URI> repositories = new LinkedHashMap<>();
        for (String name : PACKAGE_NAMES) {
            repositories.put(name, uploadPackageRepository(name));
        }
        return repositories;
    }

    private URI uploadDistribution(Map<String, URI> packages) throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : PACKAGE_NAMES) {
            environment.put(
                    name.toUpperCase(Locale.ROOT).replace('-', '_') + "_REPO",
                    packages.get(name).toString());
        }
        environment.put("AWS_S3_ENDPOINT", STORAGE.endpoint().toString());
        environment.put("WST_BUILD_IMAGE", buildImage);
        URI cliArchive = URI.create(System.getenv("WSTOOL_CLI_ARCHIVE"));
        environment.put("WSTOOL_CLI_ARCHIVE", cliArchive.toString());
        environment.put("WSTOOL_CLI_CHECKSUM", sha256(Path.of(cliArchive)));
        var credentials =
                s3.serviceClientConfiguration().credentialsProvider().resolveIdentity().join();
        environment.put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
        environment.put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
        environment.put("AWS_REGION", "us-east-1");
        repositoryEnvironment = Map.copyOf(environment);
        return uploadRepositoryFromResources(
                "distribution-" + suffix, "workspace-e2e/distribution");
    }

    private URI uploadPackageRepository(String name) throws Exception {
        return uploadRepositoryFromResources(name + "-" + suffix, "workspace-e2e/packages/" + name);
    }

    private URI uploadRepositoryFromResources(String repositoryName, String resourceFolder)
            throws Exception {
        URI uri = gitManager.create(repositoryName);
        gitManager.uploadFiles(repositoryName, resourcePath(resourceFolder));
        return uri;
    }

    private void downloadAndExtractSource(Path packageDirectory, String name, Properties sources)
            throws Exception {
        Path archive = getOrDownloadSourceArchive(name, sources);

        Path extracted = packageDirectory.resolve("source");
        Files.createDirectories(extracted);
        Process tar =
                new ProcessBuilder(
                                "tar",
                                "-xf",
                                archive.toString(),
                                "--strip-components=1",
                                "-C",
                                extracted.toString())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, tar.waitFor(), "Could not extract " + name + " source archive: " + output);
    }

    private Path getOrDownloadSourceArchive(String name, Properties sources) throws Exception {
        String expectedHash = sources.getProperty(name + ".sha256");
        Path cacheDirectory =
                Path.of(System.getProperty("user.home"), ".gradle", "caches", "wstool")
                        .resolve("workspace-e2e/sources");
        Files.createDirectories(cacheDirectory);
        Path cachedArchive = cacheDirectory.resolve(name + "-" + expectedHash + ".archive");
        if (Files.isRegularFile(cachedArchive) && expectedHash.equals(sha256(cachedArchive))) {
            return cachedArchive;
        }

        Files.deleteIfExists(cachedArchive);
        Path downloadedArchive = Files.createTempFile(cacheDirectory, name + "-", ".part");
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(sources.getProperty(name + ".url")))
                            .GET()
                            .build();
            HttpResponse<Path> response =
                    HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build()
                            .send(request, HttpResponse.BodyHandlers.ofFile(downloadedArchive));
            assertEquals(
                    200,
                    response.statusCode(),
                    "Could not download upstream source archive for " + name);
            assertEquals(
                    expectedHash,
                    sha256(downloadedArchive),
                    "Upstream source checksum mismatch for " + name);
            try {
                Files.move(
                        downloadedArchive,
                        cachedArchive,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(downloadedArchive, cachedArchive, StandardCopyOption.REPLACE_EXISTING);
            }
            return cachedArchive;
        } finally {
            Files.deleteIfExists(downloadedArchive);
        }
    }

    private String sha256(Path archive) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Properties sourceProperties() throws IOException {
        Properties properties = new Properties();
        try (var input =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("workspace-e2e/sources/sources.properties")) {
            if (input == null) {
                throw new IOException("Test source metadata is missing");
            }
            properties.load(input);
        }
        return properties;
    }

    private Path resourcePath(String name) throws Exception {
        URL resource = getClass().getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }

    private void runCli(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        processBuilder.environment().putAll(repositoryEnvironment);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "CLI failed: " + String.join(" ", arguments) + "\n" + output);
    }
}
