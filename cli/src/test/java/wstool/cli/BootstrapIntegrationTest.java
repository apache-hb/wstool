package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
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
class BootstrapIntegrationTest {
    private static final String GARAGE_IMAGE = "dxflrs/garage:v2.1.0";
    private static final String GIT_IMAGE =
            "castlemilk/git-http-backend@sha256:38c38edb7af62240da3830c9176cf5ba5374b85579d664986c481fa0eb494ab0";
    private static final String BOOTSTRAP_IMAGE = "ubuntu:24.04";
    private static final String GIT_USERNAME = "wstool";
    private static final String GIT_PASSWORD = "integration";
    private static final List<String> PACKAGE_NAMES =
            List.of(
                    "gperf",
                    "bison",
                    "flex",
                    "gawk",
                    "m4",
                    "make",
                    "perl",
                    "autoconf",
                    "automake",
                    "libtool",
                    "pkg-config",
                    "binutils",
                    "gmp",
                    "mpfr",
                    "mpc",
                    "isl",
                    "gcc",
                    "glibc",
                    "zlib",
                    "expat",
                    "libffi",
                    "freetype",
                    "fontconfig",
                    "libx11",
                    "libxext",
                    "libxrender",
                    "libxtst",
                    "libxi",
                    "alsa-lib",
                    "cups",
                    "jdk-bootstrap",
                    "openjdk");

    private static final Path repositoryRoot = findRepositoryRoot();
    private static final Path fixtureRoot =
            repositoryRoot.resolve("integration/bootstrap/fixtures");
    private static final Path integrationDirectory = prepareIntegrationDirectory();
    private static final Path gitDirectory = integrationDirectory.resolve("git");

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

    @org.testcontainers.junit.jupiter.Container
    private static final GenericContainer<?> bootstrapImage =
            new GenericContainer<>(BOOTSTRAP_IMAGE).withCommand("sleep", "infinity");

    private String garageEndpoint;
    private String accessKey;
    private String secretKey;
    private String gitEndpoint;
    private boolean scratchTagCreated;
    private String previousScratchImage;
    private String previousBootstrapImage;

    @BeforeAll
    void startServices() throws Exception {
        previousBootstrapImage = imageId(BOOTSTRAP_IMAGE);
        prepareBootstrapImage();
        garageEndpoint = "http://" + garage.getHost() + ":" + garage.getMappedPort(3900);
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
        configureGarage();
        tagScratchImage();
    }

    @AfterAll
    void restoreBuildImages() throws Exception {
        if (scratchTagCreated) {
            if (previousScratchImage == null) runDocker("image", "rm", "scratch");
            else runDocker("tag", previousScratchImage, "scratch");
        }
        if (previousBootstrapImage != null) {
            var restored = runDocker("tag", previousBootstrapImage, BOOTSTRAP_IMAGE);
            assertEquals(0, restored.exitCode, restored.output);
        }
    }

    @Test
    void bootstrapsToolchainAndBuildsOpenJdkInTheDeclaredSysroot() throws Exception {
        var workspace = integrationDirectory.resolve("workspace");
        var created =
                runCli(
                        integrationDirectory,
                        "workspace",
                        "create",
                        "--name",
                        "workspace",
                        "--root",
                        integrationDirectory.toString());
        assertEquals(0, created.exitCode, created.output);

        var setup =
                runCli(
                        workspace,
                        "workspace",
                        "setup",
                        "--distro",
                        "bootstrap",
                        "--from",
                        gitUrl("distribution"));
        assertEquals(0, setup.exitCode, setup.output);

        var cloneArguments = new ArrayList<String>(List.of("workspace", "clone"));
        for (var packageName : PACKAGE_NAMES) {
            cloneArguments.add("--package");
            cloneArguments.add(packageName);
        }
        var cloned = runCli(workspace, cloneArguments.toArray(String[]::new));
        assertEquals(0, cloned.exitCode, cloned.output);

        var built = runCli(workspace, "distro", "build", "--docker");
        assertEquals(0, built.exitCode, built.output);
        assertTrue(built.output.contains(BOOTSTRAP_IMAGE), built.output);
        assertTrue(built.output.contains("scratch"), built.output);

        var sysroot = workspace.resolve(".wstool/sysroot");
        for (var binary : List.of("m4", "make", "perl", "autoconf", "automake", "pkg-config")) {
            assertTrue(Files.isExecutable(sysroot.resolve("bin").resolve(binary)), binary);
        }
        for (var binary :
                List.of("ld", "as", "ar", "ranlib", "gcc", "g++", "cc", "java", "javac")) {
            assertTrue(Files.isExecutable(sysroot.resolve("bin").resolve(binary)), binary);
        }
        assertTrue(Files.exists(sysroot.resolve("bin/libtool")));
        assertTrue(Files.exists(sysroot.resolve("lib/libc.so.6")));
        assertTrue(Files.exists(sysroot.resolve("jdk/bin/java")));
        assertTrue(Files.exists(sysroot.resolve("lib/server/libjvm.so")));

        var java = run(List.of(sysroot.resolve("bin/java").toString(), "-version"));
        assertEquals(0, java.exitCode, java.output);
        assertTrue(java.output.contains("openjdk version \"25"), java.output);
    }

    private void configureGarage() throws Exception {
        var node = garageOutput("node", "id");
        var nodeId = node.trim().replaceAll(".*?([0-9a-f]{16,}).*", "$1");
        runGarage("layout", "assign", "-z", "dc1", "-c", "1G", nodeId);
        runGarage("layout", "apply", "--version", "1");

        var key = garageOutput("key", "create", "wstool-bootstrap-integration");
        accessKey = capture(key, "Key ID:\\s*(\\S+)");
        secretKey = capture(key, "Secret key:\\s*(\\S+)");
        runGarage("bucket", "create", "bootstrap");
        runGarage(
                "bucket",
                "allow",
                "--read",
                "--write",
                "bootstrap",
                "--key",
                "wstool-bootstrap-integration");
    }

    private void tagScratchImage() throws Exception {
        var existing = runDocker("image", "inspect", "--format", "{{.Id}}", "scratch");
        if (existing.exitCode == 0) previousScratchImage = existing.output.trim();
        var tagged = runDocker("tag", BOOTSTRAP_IMAGE, "scratch");
        assertEquals(0, tagged.exitCode, tagged.output);
        scratchTagCreated = true;
    }

    private void prepareBootstrapImage() throws Exception {
        var update = bootstrapImage.execInContainer("apt-get", "update");
        assertEquals(0, update.getExitCode(), update.getStderr());
        var install =
                bootstrapImage.execInContainer(
                        "apt-get",
                        "install",
                        "-y",
                        "--no-install-recommends",
                        "autoconf",
                        "automake",
                        "bison",
                        "build-essential",
                        "ca-certificates",
                        "cpio",
                        "file",
                        "flex",
                        "gawk",
                        "help2man",
                        "libasound2-dev",
                        "libcups2-dev",
                        "libfontconfig1-dev",
                        "libfreetype-dev",
                        "libncurses-dev",
                        "libreadline-dev",
                        "libxcb1-dev",
                        "libxau-dev",
                        "libxdmcp-dev",
                        "libtool",
                        "libx11-dev",
                        "libxext-dev",
                        "libxi-dev",
                        "libxrender-dev",
                        "libxtst-dev",
                        "m4",
                        "make",
                        "perl",
                        "pkg-config",
                        "python3",
                        "texinfo",
                        "unzip",
                        "xz-utils",
                        "zip");
        assertEquals(0, install.getExitCode(), install.getStderr());
        var committed = runDocker("commit", bootstrapImage.getContainerId(), BOOTSTRAP_IMAGE);
        assertEquals(0, committed.exitCode, committed.output);
    }

    private static String imageId(String image) throws Exception {
        var result = runDocker("image", "inspect", "--format", "{{.Id}}", image);
        return result.exitCode == 0 ? result.output.trim() : null;
    }

    private void publishRepositories() throws Exception {
        publishRepository("distribution", createDistributionSource());
        for (var packageName : PACKAGE_NAMES) {
            publishRepository(packageName, fixtureRoot.resolve("packages").resolve(packageName));
        }
    }

    private Path createDistributionSource() throws IOException {
        var source = integrationDirectory.resolve("distribution-source");
        Files.createDirectories(source);
        var world = Files.readString(fixtureRoot.resolve("distribution/world.pkl"));
        Files.writeString(
                source.resolve("world.pkl"), world.replace("__GIT_SERVER__", gitEndpoint));
        return source;
    }

    private void publishRepository(String name, Path source) throws Exception {
        assertTrue(Files.isDirectory(source), source.toString());
        var working = integrationDirectory.resolve("git-working").resolve(name);
        copy(source, working);
        runGit(working, "init", "--initial-branch", "master");
        runGit(working, "config", "user.name", "wstool integration");
        runGit(working, "config", "user.email", "wstool@example.invalid");
        runGit(working, "add", ".");
        runGit(working, "commit", "-m", "fixture");
        var bare = gitDirectory.resolve(name + ".git");
        var result =
                run(
                        new ProcessBuilder(
                                "git", "clone", "--bare", working.toString(), bare.toString()));
        assertEquals(0, result.exitCode, result.output);
    }

    private String gitUrl(String name) {
        return gitEndpoint + "/" + name + ".git";
    }

    private Result runCli(Path directory, String... arguments) throws Exception {
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
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(directory.toFile());
        process.environment().put("WSTOOL_S3_ENDPOINT", garageEndpoint);
        process.environment().put("WSTOOL_S3_ACCESS_KEY", accessKey);
        process.environment().put("WSTOOL_S3_SECRET_KEY", secretKey);
        process.environment().put("WSTOOL_S3_BUCKET", "bootstrap");
        return run(process);
    }

    private static void runGit(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));
        var result = run(new ProcessBuilder(command));
        assertEquals(0, result.exitCode, result.output);
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
        var matcher =
                java.util.regex.Pattern.compile(expression, java.util.regex.Pattern.MULTILINE)
                        .matcher(text);
        if (!matcher.find())
            throw new IllegalStateException("Did not find " + expression + " in " + text);
        return matcher.group(1);
    }

    private static Result runDocker(String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of("docker"));
        command.addAll(List.of(arguments));
        return run(new ProcessBuilder(command));
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

    private static Path findRepositoryRoot() {
        var path = Path.of(".").toAbsolutePath().normalize();
        while (path != null) {
            if (Files.isDirectory(path.resolve("integration/bootstrap/fixtures"))) return path;
            path = path.getParent();
        }
        throw new IllegalStateException("Cannot find repository root");
    }

    private static Path prepareIntegrationDirectory() {
        var directory = findRepositoryRoot().resolve("build/bootstrap-integration-test");
        try {
            removeGeneratedDirectory(directory);
            Files.createDirectories(directory.resolve("git"));
            return directory;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to prepare integration directory", e);
        }
    }

    private static void removeGeneratedDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        var result =
                run(
                        new ProcessBuilder(
                                "docker",
                                "run",
                                "--rm",
                                "-v",
                                directory.getParent() + ":/cleanup",
                                BOOTSTRAP_IMAGE,
                                "rm",
                                "-rf",
                                "/cleanup/" + directory.getFileName()));
        if (result.exitCode != 0)
            throw new IllegalStateException(
                    "Unable to remove integration directory: " + result.output);
    }

    private static void copy(Path source, Path target) {
        try (var paths = Files.walk(source)) {
            paths.forEach(
                    path -> {
                        var destination = target.resolve(source.relativize(path).toString());
                        try {
                            if (Files.isSymbolicLink(path)) {
                                Files.createDirectories(destination.getParent());
                                Files.createSymbolicLink(destination, Files.readSymbolicLink(path));
                            } else if (Files.isDirectory(path)) {
                                Files.createDirectories(destination);
                            } else {
                                Files.createDirectories(destination.getParent());
                                Files.copy(
                                        path,
                                        destination,
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.COPY_ATTRIBUTES);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to copy fixture " + source, e);
        }
    }

    private record Result(int exitCode, String output) {}
}
