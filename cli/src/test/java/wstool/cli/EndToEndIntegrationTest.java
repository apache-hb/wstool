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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

    private final Path fixtureRoot = findFixtureRoot();
    private final Path repositoryRoot = fixtureRoot.getParent().getParent();
    private Path temporaryDirectory;
    private String endpoint;
    private String accessKey;
    private String secretKey;

    @BeforeAll
    void startGarage() throws Exception {
        temporaryDirectory = Files.createTempDirectory("wstool-integration-");
        endpoint = "http://" + garage.getHost() + ":" + garage.getMappedPort(3900);

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

    @AfterAll
    void cleanupTemporaryDirectory() throws Exception {
        if (temporaryDirectory != null) delete(temporaryDirectory);
    }

    @Test
    void buildsCxxApplicationAndFetchesPublishedDependencies() throws Exception {
        var firstWorkspace = temporaryDirectory.resolve("first");
        createWorkspace(firstWorkspace, "first");
        var firstCreated =
                runCli(
                        firstWorkspace.getParent(),
                        "workspace",
                        "create",
                        "--name",
                        "first",
                        "--root",
                        temporaryDirectory.toString());
        assertEquals(0, firstCreated.exitCode, firstCreated.output);
        assertTrue(Files.isDirectory(firstWorkspace), firstCreated.output);
        configureAndClone(
                firstWorkspace, "wstool-cmake", repositoryRoot.resolve("packages/wstool-cmake"));
        configureAndClone(firstWorkspace, "abseil", "abseil");
        configureAndClone(firstWorkspace, "googletest", "googletest");
        configureAndClone(firstWorkspace, "glibc", "glibc");
        configureAndClone(firstWorkspace, "sample-app", "app");
        runCli(firstWorkspace, "workspace", "setup", "--distro", "demo");
        var firstBuild = runCli(firstWorkspace, "distro", "build");
        assertEquals(0, firstBuild.exitCode, firstBuild.output);
        assertTrue(firstBuild.output.contains("Built distribution"));

        var binary = firstWorkspace.resolve(".wstool/stage/sample-app/bin/sample-app");
        var application = run(List.of(binary.toString()));
        assertEquals(0, application.exitCode, application.output);
        assertTrue(application.output.startsWith("wstool/"), application.output);

        var secondWorkspace = temporaryDirectory.resolve("second");
        var secondCreated =
                runCli(
                        secondWorkspace.getParent(),
                        "workspace",
                        "create",
                        "--name",
                        "second",
                        "--root",
                        temporaryDirectory.toString());
        assertEquals(0, secondCreated.exitCode, secondCreated.output);
        assertTrue(Files.isDirectory(secondWorkspace), secondCreated.output);
        configureAndClone(secondWorkspace, "sample-app", "app");
        runCli(secondWorkspace, "workspace", "setup", "--distro", "demo");
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

    private void configureAndClone(Path workspace, String packageName, String fixtureName)
            throws Exception {
        configureAndClone(workspace, packageName, fixtureRoot.resolve(fixtureName));
    }

    private void configureAndClone(Path workspace, String packageName, Path source)
            throws Exception {
        if (!Files.exists(workspace.resolve(".wstool/workspace.properties"))) {
            runCli(workspace, "workspace", "setup", "--distro", "demo");
        }
        var result =
                runCli(
                        workspace,
                        "workspace",
                        "clone",
                        "--package",
                        packageName,
                        "--from",
                        source.toString());
        assertEquals(0, result.exitCode, result.output);
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
