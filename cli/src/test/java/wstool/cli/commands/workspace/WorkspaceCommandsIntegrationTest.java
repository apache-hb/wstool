package wstool.cli.commands.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.test.components.PackageGitManager;

@Testcontainers(disabledWithoutDocker = true)
class WorkspaceCommandsIntegrationTest {
    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    private Path tmp;
    private PackageGitManager git;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        tmp = Path.of("build").resolve(testInfo.getTestMethod().orElseThrow().getName());
        git = new PackageGitManager(tmp, GITEA);
    }

    @Test
    void createCreatesWorkspaceAndClonesDistribution() throws Exception {
        URI distribution =
                createRepository("distribution", resourceDirectory("/workspace-add-remove/distribution/initial"));

        CliResult result =
                runCli(
                        tmp,
                        Map.of(),
                        "workspace",
                        "create",
                        "--name",
                        "created-workspace",
                        "--distribution",
                        distribution.toString());

        assertSuccessful(result);
        Path workspace = tmp.resolve("created-workspace");
        assertTrue(Files.isDirectory(workspace.resolve("distribution/.git")), result.output());
        assertTrue(Files.isDirectory(workspace.resolve("src")), result.output());
        assertTrue(Files.isDirectory(workspace.resolve("private/cache")), result.output());
        assertTrue(Files.isDirectory(workspace.resolve("private/prefix")), result.output());
        assertTrue(Files.isDirectory(workspace.resolve("private/build")), result.output());
        assertTrue(Files.isRegularFile(workspace.resolve("wstool.db")), result.output());
    }

    @Test
    void addClonesPackageFromWorkspaceDistribution() throws Exception {
        URI packageRepository = createSamplePackage();
        Path workspace = createWorkspace("add-workspace", packageRepository);

        CliResult result =
                runCli(
                        workspace,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "add",
                        "--package",
                        "sample");

        assertSuccessful(result);
        assertTrue(Files.isDirectory(workspace.resolve("src/sample/.git")), result.output());
        assertEquals(
                "workspace add remove fixture\n",
                Files.readString(workspace.resolve("src/sample/README.md")));
        assertTrue(Files.isRegularFile(workspace.resolve("src/sample/wstool.pkl")), result.output());
    }

    @Test
    void removeDeletesClonedPackageFromWorkspace() throws Exception {
        URI packageRepository = createSamplePackage();
        Path workspace = createWorkspace("remove-workspace", packageRepository);
        CliResult addResult =
                runCli(
                        workspace,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "add",
                        "--package",
                        "sample");
        assertSuccessful(addResult);
        assertTrue(Files.exists(workspace.resolve("src/sample")), addResult.output());

        CliResult removeResult =
                runCli(
                        workspace,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "remove",
                        "--package",
                        "sample");

        assertSuccessful(removeResult);
        assertFalse(Files.exists(workspace.resolve("src/sample")), removeResult.output());
    }

    private URI createSamplePackage() throws Exception {
        return createRepository("sample", resourceDirectory("/workspace-add-remove/packages/sample"));
    }

    private URI createRepository(String name, Path contents) throws Exception {
        String repository = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI uri = git.create(repository);
        git.uploadFiles(repository, contents);
        return uri;
    }

    private Path createWorkspace(String name, URI packageRepository) throws Exception {
        URI distribution =
                createRepository(
                        "distribution",
                        resourceDirectory("/workspace-add-remove/distribution/updated"));
        Path workspace = tmp.resolve(name);
        CliResult result =
                runCli(
                        tmp,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "create",
                        "--name",
                        name,
                        "--distribution",
                        distribution.toString());
        assertSuccessful(result);
        assertTrue(Files.isDirectory(workspace.resolve("distribution")), result.output());
        return workspace;
    }

    private CliResult runCli(Path cwd, Map<String, String> environment, String... arguments)
            throws Exception {
        String classpath = System.getProperty("wstool.cli.classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Missing wstool.cli.classpath test system property");
        }

        Path output = tmp.resolve("cli-output.txt");
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dwstool.version=integration-test");
        command.add("-cp");
        command.add(classpath);
        command.add("wstool.cli.Main");
        command.addAll(java.util.List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        builder.environment().remove("PACKAGE_REPOSITORY");
        builder.environment().putAll(environment);

        Process process = builder.start();
        if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("wstool CLI did not finish within two minutes");
        }
        return new CliResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    private static void assertSuccessful(CliResult result) {
        assertEquals(0, result.exitCode(), result.output());
    }

    private static Path resourceDirectory(String resourceName) throws Exception {
        var resource = WorkspaceCommandsIntegrationTest.class.getResource(resourceName);
        if (resource == null) {
            throw new IOException("Missing test resource " + resourceName);
        }
        return Path.of(resource.toURI());
    }

    private record CliResult(int exitCode, String output) {}
}
