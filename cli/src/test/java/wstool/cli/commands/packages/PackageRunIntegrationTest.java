package wstool.cli.commands.packages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.test.components.PackageGitManager;

@Testcontainers(disabledWithoutDocker = true)
class PackageRunIntegrationTest {
    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    private Path tmp;

    private PackageGitManager git;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        tmp =
                Path.of("build", "package-run", testInfo.getTestMethod().orElseThrow().getName())
                        .toAbsolutePath();
        if (Files.exists(tmp)) {
            PathUtils.deleteDirectory(tmp);
        }
        Files.createDirectories(tmp);
        git = new PackageGitManager(tmp, GITEA);
    }

    @Test
    void runsBuildToolFromCurrentPackage() throws Exception {
        URI appRepository =
                createRepository("local-app", resourceDirectory("/package-run/local/packages/local-app"));
        Map<String, String> environment = Map.of("LOCAL_APP_REPOSITORY", appRepository.toString());
        Path workspace = createWorkspace("local", "/package-run/local/distribution", environment);
        addPackage(workspace, "local-app", environment);
        Path packageDirectory = workspace.resolve("src/local-app");

        CliResult result =
                runPackage(packageDirectory, environment, "verify", "argument with spaces");

        assertSuccess(result);
        Path prefix = workspace.resolve("private/prefix/local-app/1.0.0");
        assertEquals("verify\nargument with spaces\n", Files.readString(prefix.resolve("arguments.txt")));
        assertEquals(packageDirectory.toAbsolutePath() + "\n", Files.readString(prefix.resolve("source-dir.txt")));
    }

    @Test
    void runsBuildToolFromBuildDependency() throws Exception {
        URI appRepository =
                createRepository("dependency-app", resourceDirectory("/package-run/dependency/packages/dependency-app"));
        URI toolRepository =
                createRepository("tool-provider", resourceDirectory("/package-run/dependency/packages/tool-provider"));
        Map<String, String> environment =
                Map.of(
                        "DEPENDENCY_APP_REPOSITORY", appRepository.toString(),
                        "DEPENDENCY_TOOL_REPOSITORY", toolRepository.toString());
        Path workspace = createWorkspace("dependency", "/package-run/dependency/distribution", environment);
        addPackage(workspace, "dependency-app", environment);
        addPackage(workspace, "tool-provider", environment);
        installPackageTools(workspace, "tool-provider");

        CliResult result =
                runPackage(
                        workspace.resolve("src/dependency-app"),
                        environment,
                        "compile",
                        "dependency argument");

        assertSuccess(result);
        assertEquals(
                "provided by dependency: compile dependency argument\n",
                Files.readString(workspace.resolve("private/prefix/dependency-app/1.0.0/result.txt")));
    }

    @Test
    void buildToolCanUseToolFromItsBuildDependency() throws Exception {
        URI appRepository =
                createRepository("transitive-app", resourceDirectory("/package-run/transitive/packages/transitive-app"));
        URI ownerRepository =
                createRepository("tool-owner", resourceDirectory("/package-run/transitive/packages/tool-owner"));
        URI helperRepository =
                createRepository("tool-helper", resourceDirectory("/package-run/transitive/packages/tool-helper"));
        Map<String, String> environment =
                Map.of(
                        "TRANSITIVE_APP_REPOSITORY", appRepository.toString(),
                        "TOOL_OWNER_REPOSITORY", ownerRepository.toString(),
                        "TOOL_HELPER_REPOSITORY", helperRepository.toString());
        Path workspace = createWorkspace("transitive", "/package-run/transitive/distribution", environment);
        addPackage(workspace, "transitive-app", environment);
        addPackage(workspace, "tool-owner", environment);
        addPackage(workspace, "tool-helper", environment);
        installPackageTools(workspace, "tool-owner");
        installPackageTools(workspace, "tool-helper");

        CliResult result =
                runPackage(workspace.resolve("src/transitive-app"), environment, "release");

        assertSuccess(result);
        assertEquals(
                "owner received: helper from dependency\n",
                Files.readString(workspace.resolve("private/prefix/transitive-app/1.0.0/result.txt")));
    }

    private Path createWorkspace(String name, String distributionFixture, Map<String, String> environment)
            throws Exception {
        URI distribution = createRepository("distribution", resourceDirectory(distributionFixture));
        CliResult result =
                runCli(
                        tmp,
                        environment,
                        "workspace",
                        "create",
                        "--name",
                        name,
                        "--distribution",
                        distribution.toString());
        assertSuccess(result);
        return tmp.resolve(name);
    }

    private void addPackage(Path workspace, String packageName, Map<String, String> environment)
            throws Exception {
        assertSuccess(runCli(workspace, environment, "workspace", "add", "--package", packageName));
    }

    private CliResult runPackage(
            Path packageDirectory, Map<String, String> environment, String... targetArguments) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("package", "run"));
        arguments.addAll(List.of(targetArguments));
        return runCli(packageDirectory, environment, arguments.toArray(String[]::new));
    }

    private void installPackageTools(Path workspace, String packageName) throws Exception {
        Path sourceBin = workspace.resolve("src").resolve(packageName).resolve("bin");
        Path targetBin = workspace.resolve("private/prefix").resolve(packageName).resolve("1.0.0/bin");
        Files.createDirectories(targetBin);
        try (var files = Files.walk(sourceBin)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Files.copy(
                        source,
                        targetBin.resolve(sourceBin.relativize(source)),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private URI createRepository(String name, Path contents) throws Exception {
        String repository = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI uri = git.create(repository);
        git.uploadFiles(repository, contents);
        return uri;
    }

    private CliResult runCli(Path cwd, Map<String, String> environment, String... arguments)
            throws Exception {
        String classpath = System.getProperty("wstool.cli.classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Missing wstool.cli.classpath test system property");
        }

        Path output = tmp.resolve("cli-output.txt");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dwstool.version=integration-test");
        command.add("-cp");
        command.add(classpath);
        command.add("wstool.cli.Main");
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        builder.environment().putAll(environment);

        Process process = builder.start();
        if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("wstool CLI did not finish within two minutes");
        }
        return new CliResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    private static void assertSuccess(CliResult result) {
        assertEquals(0, result.exitCode(), result.output());
    }

    private static Path resourceDirectory(String resourceName) throws Exception {
        var resource = PackageRunIntegrationTest.class.getResource(resourceName);
        if (resource == null) {
            throw new IOException("Missing test resource " + resourceName);
        }
        return Path.of(resource.toURI());
    }

    private record CliResult(int exitCode, String output) {}
}
