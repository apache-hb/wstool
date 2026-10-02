package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndIntegrationTest {
    private static final String GARAGE_IMAGE = "dxflrs/garage:v2.1.0";
    private static final String GIT_IMAGE =
            "castlemilk/git-http-backend@sha256:38c38edb7af62240da3830c9176cf5ba5374b85579d664986c481fa0eb494ab0";
    private static final String GIT_USERNAME = "wstool";
    private static final String GIT_PASSWORD = "integration";

    private static final Path fixtureRoot = findFixtureRoot();
    private static final Path repositoryRoot = fixtureRoot.getParent().getParent();
    private static final Path integrationDirectory = prepareIntegrationDirectory();
    private static final Path integrationCacheDirectory =
            IntegrationRepositoryCache.createDirectory(
                    repositoryRoot.resolve(".gradle/integration-test-cache/end-to-end"));
    private static final Path downloadCacheDirectory =
            IntegrationRepositoryCache.createDirectory(
                    repositoryRoot.resolve(".gradle/integration-test-cache/downloads"));
    private static final Path gitDirectory =
            IntegrationRepositoryCache.createDirectory(integrationCacheDirectory.resolve("git"));

    @org.testcontainers.junit.jupiter.Container
    private static final GenericContainer<?> garage =
            new GenericContainer<>(GARAGE_IMAGE)
                    .withExposedPorts(3900)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("garage.toml"), "/etc/garage.toml")
                    .waitingFor(
                            Wait.forHttp("/")
                                    .forPort(3900)
                                    .forStatusCodeMatching(status -> status >= 200 && status < 500)
                                    .withStartupTimeout(Duration.ofSeconds(45)));

    @org.testcontainers.junit.jupiter.Container
    private static final GenericContainer<?> gitServer =
            new GenericContainer<>(GIT_IMAGE)
                    .withExposedPorts(3000)
                    .withFileSystemBind(gitDirectory.toString(), "/tmp/git", BindMode.READ_WRITE)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("gitconfig"), "/etc/gitconfig")
                    .withEnv("GIT_USERNAME", GIT_USERNAME)
                    .withEnv("GIT_PASSWORD", GIT_PASSWORD)
                    .waitingFor(
                            Wait.forHttp("/")
                                    .forPort(3000)
                                    .forStatusCodeMatching(status -> status >= 200 && status < 500)
                                    .withStartupTimeout(Duration.ofSeconds(45)));

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String gitEndpoint;

    @BeforeAll
    void startServices() throws Exception {
        endpoint = "http://" + garage.getHost() + ":" + garage.getMappedPort(3900);
        gitEndpoint =
                "http://"
                        + GIT_USERNAME
                        + ":"
                        + GIT_PASSWORD
                        + "@"
                        + gitServer.getHost()
                        + ":"
                        + gitServer.getMappedPort(3000);
        publishRepositories();

        var node = garageOutput("node", "id");
        var nodeId =
                Pattern.compile("[0-9a-f]{16,}")
                        .matcher(node)
                        .results()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Could not determine Garage node id from: " + node))
                        .group();
        runGarage("layout", "assign", "-z", "dc1", "-c", "1G", nodeId);
        runGarage("layout", "apply", "--version", "1");

        var key = garageOutput("key", "create", "wstool-integration");
        accessKey = capture(key, "Key ID:\\s*(\\S+)");
        secretKey = capture(key, "Secret key:\\s*(\\S+)");
        runGarage("bucket", "create", "storage");
        runGarage("bucket", "allow", "--read", "--write", "storage", "--key", "wstool-integration");
    }

    @Test
    void buildsCxxApplicationAndFetchesPublishedDependencies() throws Exception {
        var firstWorkspace = integrationDirectory.resolve("first");
        createWorkspace(firstWorkspace, "first");
        var firstCreated =
                runCli(
                        firstWorkspace.getParent(),
                        "workspace",
                        "create",
                        "--name",
                        "first",
                        "--root",
                        integrationDirectory.toString());
        assertEquals(0, firstCreated.exitCode, firstCreated.output);
        assertTrue(Files.isDirectory(firstWorkspace), firstCreated.output);
        configure(firstWorkspace);
        configureAndClone(firstWorkspace, "wstool-cmake");
        configureAndClone(firstWorkspace, "abseil");
        configureAndClone(firstWorkspace, "googletest");
        configureAndClone(firstWorkspace, "glibc");
        configureAndClone(firstWorkspace, "sample-app");
        assertBuildDirectoryLink(firstWorkspace, "abseil");
        assertBuildDirectoryLink(firstWorkspace, "googletest");
        assertBuildDirectoryLink(firstWorkspace, "glibc");
        assertBuildDirectoryLink(firstWorkspace, "sample-app");
        var firstBuild = runCli(firstWorkspace, "distro", "build");
        assertEquals(0, firstBuild.exitCode, firstBuild.output);
        assertTrue(firstBuild.output.contains("Built distribution"));
        assertTrue(
                Files.exists(
                        firstWorkspace.resolve(".wstool/stage/glibc/include/gnu/libc-version.h")));
        assertTrue(Files.exists(firstWorkspace.resolve(".wstool/stage/glibc/lib/libc.so.6")));

        var binary = firstWorkspace.resolve(".wstool/stage/sample-app/bin/sample-app");
        var application = run(List.of(binary.toString()));
        assertEquals(0, application.exitCode, application.output);
        assertTrue(application.output.startsWith("wstool/"), application.output);

        var secondWorkspace = integrationDirectory.resolve("second");
        var secondCreated =
                runCli(
                        secondWorkspace.getParent(),
                        "workspace",
                        "create",
                        "--name",
                        "second",
                        "--root",
                        integrationDirectory.toString());
        assertEquals(0, secondCreated.exitCode, secondCreated.output);
        assertTrue(Files.isDirectory(secondWorkspace), secondCreated.output);
        configure(secondWorkspace);
        configureAndClone(secondWorkspace, "sample-app");
        assertBuildDirectoryLink(secondWorkspace, "sample-app");
        var fetchedBuild = runCli(secondWorkspace, "distro", "build");
        assertEquals(0, fetchedBuild.exitCode, fetchedBuild.output);
        assertTrue(Files.exists(secondWorkspace.resolve(".wstool/sysroot/lib/libabsl_strings.a")));
        assertTrue(Files.exists(secondWorkspace.resolve(".wstool/sysroot/lib/libgtest_main.a")));
        assertEquals(
                0,
                run(List.of(
                                secondWorkspace
                                        .resolve(".wstool/stage/sample-app/bin/sample-app")
                                        .toString()))
                        .exitCode);
    }

    private void createWorkspace(Path workspace, String name) throws Exception {
        assertTrue(Files.notExists(workspace), name);
    }

    private static void assertBuildDirectoryLink(Path workspace, String packageName)
            throws IOException {
        var link = workspace.resolve("src").resolve(packageName).resolve("builddir");
        var expected = workspace.resolve(".wstool/build").resolve(packageName).toAbsolutePath();
        assertTrue(Files.isSymbolicLink(link), link.toString());
        var actual = link.getParent().resolve(Files.readSymbolicLink(link)).toAbsolutePath();
        assertEquals(expected.normalize(), actual.normalize());
    }

    private static void assertWorkspaceLayout(Path workspace) throws IOException {
        assertTrue(Files.isSymbolicLink(workspace.resolve("world.pkl")));
        assertEquals(
                Path.of("distribution", "world.pkl"),
                Files.readSymbolicLink(workspace.resolve("world.pkl")));
        assertTrue(Files.isDirectory(workspace.resolve("src")));
        assertTrue(Files.isDirectory(workspace.resolve(".wstool")));
    }

    private void configureAndClone(Path workspace, String packageName) throws Exception {
        var result = runCli(workspace, "workspace", "clone", "--package", packageName);
        assertEquals(0, result.exitCode, result.output);
    }

    private void configure(Path workspace) throws Exception {
        var setup =
                runCli(
                        workspace,
                        "workspace",
                        "setup",
                        "--distro",
                        "demo",
                        "--from",
                        gitUrl("distribution"));
        assertEquals(0, setup.exitCode, setup.output);
        assertWorkspaceLayout(workspace);
    }

    private void publishRepositories() throws Exception {
        publishRepository("distribution", createDistributionSource(), false);
        publishRepository("wstool-cmake", repositoryRoot.resolve("packages/wstool-cmake"), true);
        publishRepository("abseil", fixtureRoot.resolve("abseil"), true);
        publishRepository("googletest", fixtureRoot.resolve("googletest"), true);
        publishRepository("glibc", fixtureRoot.resolve("glibc"), true);
        publishRepository("sample-app", fixtureRoot.resolve("app"), true);
    }

    private Path createDistributionSource() throws IOException {
        var source = integrationDirectory.resolve("distribution-source");
        Files.createDirectories(source);
        var world = Files.readString(fixtureRoot.resolve("distribution/world.pkl"));
        Files.writeString(
                source.resolve("world.pkl"), world.replace("__GIT_SERVER__", gitEndpoint));
        return source;
    }

    private void publishRepository(String name, Path source, boolean persistent) throws Exception {
        IntegrationRepositoryCache.publish(
                name,
                source,
                integrationDirectory.resolve("git-working"),
                gitDirectory,
                integrationCacheDirectory.resolve("repository-state"),
                persistent);
    }

    private String gitUrl(String name) {
        return gitEndpoint + "/" + name + ".git";
    }

    private Result runCli(Path directory, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("java");
        command.add("-cp");
        command.add(
                Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                        .map(
                                entry -> {
                                    var path = Path.of(entry);
                                    return (path.isAbsolute()
                                                    ? path
                                                    : Path.of(System.getProperty("user.dir"))
                                                            .resolve(path))
                                            .toString();
                                })
                        .collect(Collectors.joining(File.pathSeparator)));
        command.add("wstool.cli.RootCommand");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(directory.toFile());
        process.environment().put("WSTOOL_S3_ENDPOINT", endpoint);
        process.environment().put("WSTOOL_S3_ACCESS_KEY", accessKey);
        process.environment().put("WSTOOL_S3_SECRET_KEY", secretKey);
        process.environment().put("WSTOOL_S3_BUCKET", "storage");
        process.environment().put("WSTOOL_DOWNLOAD_CACHE", downloadCacheDirectory.toString());
        return run(process);
    }

    private static String garageOutput(String... command) throws Exception {
        var result = executeGarage(command);
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout();
    }

    private static void runGarage(String... command) throws Exception {
        var result = executeGarage(command);
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    private static Container.ExecResult executeGarage(String... command) throws Exception {
        var garageCommand = new String[command.length + 1];
        garageCommand[0] = "/garage";
        System.arraycopy(command, 0, garageCommand, 1, command.length);
        return garage.execInContainer(garageCommand);
    }

    private static String capture(String text, String expression) {
        var matcher = Pattern.compile(expression, Pattern.MULTILINE).matcher(text);
        if (!matcher.find())
            throw new IllegalStateException("Did not find " + expression + " in " + text);
        return matcher.group(1);
    }

    private static Result run(List<String> command) throws Exception {
        return run(new ProcessBuilder(command));
    }

    private static Result run(ProcessBuilder builder) throws Exception {
        builder.redirectErrorStream(true);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes());
        var exitCode = process.waitFor();
        return new Result(exitCode, output);
    }

    private static Path findFixtureRoot() {
        var path = Path.of(".").toAbsolutePath().normalize();
        while (path != null) {
            var candidate = path.resolve("integration/fixtures");
            if (Files.exists(candidate.resolve("abseil"))) return candidate;
            path = path.getParent();
        }
        throw new IllegalStateException(
                "Cannot find integration/fixtures from " + Path.of(".").toAbsolutePath());
    }

    private static Path prepareIntegrationDirectory() {
        var directory = repositoryRoot.resolve("build/integration-test");
        try {
            delete(directory);
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to prepare integration directory", e);
        }
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            item -> {
                                try {
                                    Files.deleteIfExists(item);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        }
    }

    private record Result(int exitCode, String output) {}
}
