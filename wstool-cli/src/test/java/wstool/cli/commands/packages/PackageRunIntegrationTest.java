package wstool.cli.commands.packages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.cli.commands.CliHelper;
import wstool.test.components.CliResult;
import wstool.test.components.PackageGitManager;

@Testcontainers(disabledWithoutDocker = true)
class PackageRunIntegrationTest {
    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    private Path tmp;
    private PackageGitManager git;
    private CliHelper cli;

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
        cli = new CliHelper(tmp);
    }

    @Test
    void runsBuildToolFromCurrentPackage() throws Exception {
        URI appRepository =
                createRepository(
                        "local-app",
                        CliHelper.resourceDirectory("/package-run/local/packages/local-app"));
        Map<String, String> environment = Map.of("LOCAL_APP_REPOSITORY", appRepository.toString());
        Path workspace = createWorkspace("local", "/package-run/local/distribution", environment);
        addPackage(workspace, "local-app", environment);
        Path packageDirectory = workspace.resolve("src/local-app");

        CliResult result =
                runPackage(packageDirectory, environment, "verify", "argument with spaces");

        CliHelper.assertSuccess(result);
        Path prefix = workspace.resolve("private/prefix/local-app/1.0.0");
        assertEquals(
                "verify\nargument with spaces\n",
                Files.readString(prefix.resolve("arguments.txt")));
        assertEquals(
                packageDirectory.toAbsolutePath() + "\n",
                Files.readString(prefix.resolve("source-dir.txt")));
    }

    @Test
    void runsBuildToolFromBuildDependency() throws Exception {
        URI appRepository =
                createRepository(
                        "dependency-app",
                        CliHelper.resourceDirectory(
                                "/package-run/dependency/packages/dependency-app"));
        URI toolRepository =
                createRepository(
                        "tool-provider",
                        CliHelper.resourceDirectory(
                                "/package-run/dependency/packages/tool-provider"));
        Map<String, String> environment =
                Map.of(
                        "DEPENDENCY_APP_REPOSITORY", appRepository.toString(),
                        "DEPENDENCY_TOOL_REPOSITORY", toolRepository.toString());
        Path workspace =
                createWorkspace("dependency", "/package-run/dependency/distribution", environment);
        addPackage(workspace, "dependency-app", environment);
        addPackage(workspace, "tool-provider", environment);
        cli.checkCli(
                workspace.resolve("src/tool-provider"), environment, "package", "run", "release");

        CliResult result =
                runPackage(
                        workspace.resolve("src/dependency-app"),
                        environment,
                        "compile",
                        "dependency argument");

        CliHelper.assertSuccess(result);
        assertEquals(
                "provided by dependency: compile dependency argument\n",
                Files.readString(
                        workspace.resolve("private/prefix/dependency-app/1.0.0/result.txt")));
    }

    @Test
    void buildToolCanUseToolFromItsBuildDependency() throws Exception {
        URI appRepository =
                createRepository(
                        "transitive-app",
                        CliHelper.resourceDirectory(
                                "/package-run/transitive/packages/transitive-app"));
        URI ownerRepository =
                createRepository(
                        "tool-owner",
                        CliHelper.resourceDirectory("/package-run/transitive/packages/tool-owner"));
        URI helperRepository =
                createRepository(
                        "tool-helper",
                        CliHelper.resourceDirectory(
                                "/package-run/transitive/packages/tool-helper"));

        Map<String, String> environment =
                Map.of(
                        "TRANSITIVE_APP_REPOSITORY", appRepository.toString(),
                        "TOOL_OWNER_REPOSITORY", ownerRepository.toString(),
                        "TOOL_HELPER_REPOSITORY", helperRepository.toString());

        Path workspace =
                createWorkspace("transitive", "/package-run/transitive/distribution", environment);
        addPackage(workspace, "transitive-app", environment);
        addPackage(workspace, "tool-owner", environment);
        addPackage(workspace, "tool-helper", environment);

        cli.checkCli(
                workspace.resolve("src/tool-helper"), environment, "package", "run", "release");
        cli.checkCli(workspace.resolve("src/tool-owner"), environment, "package", "run", "release");

        CliResult result =
                runPackage(workspace.resolve("src/transitive-app"), environment, "release");

        CliHelper.assertSuccess(result);
        assertEquals(
                "owner received: helper from dependency\n",
                Files.readString(
                        workspace.resolve("private/prefix/transitive-app/1.0.0/result.txt")));
    }

    private Path createWorkspace(
            String name, String distributionFixture, Map<String, String> environment)
            throws Exception {
        URI distribution =
                createRepository("distribution", CliHelper.resourceDirectory(distributionFixture));
        CliResult result =
                cli.runCli(
                        tmp,
                        environment,
                        "workspace",
                        "create",
                        "--name",
                        name,
                        "--distribution",
                        distribution.toString());
        CliHelper.assertSuccess(result);
        return tmp.resolve(name);
    }

    private void addPackage(Path workspace, String packageName, Map<String, String> environment)
            throws Exception {
        CliHelper.assertSuccess(
                cli.runCli(workspace, environment, "workspace", "add", "--package", packageName));
    }

    private CliResult runPackage(
            Path packageDirectory, Map<String, String> environment, String... targetArguments)
            throws Exception {
        List<String> arguments = new ArrayList<>(List.of("package", "run"));
        arguments.addAll(List.of(targetArguments));
        return cli.runCli(packageDirectory, environment, arguments.toArray(String[]::new));
    }

    private URI createRepository(String name, Path contents) throws Exception {
        String repository = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI uri = git.create(repository);
        git.uploadFiles(repository, contents);
        return uri;
    }
}
