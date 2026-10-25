package wstool.cli.commands.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.cli.commands.CliHelper;
import wstool.test.components.CliResult;
import wstool.test.components.PackageGitManager;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class WorkspaceCommandsIntegrationTest {
    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    private Path tmp;
    private CliHelper cli;
    private PackageGitManager git;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        tmp =
                Path.of("build", "workspace-commands")
                        .resolve(testInfo.getTestMethod().orElseThrow().getName());

        if (Files.exists(tmp)) {
            PathUtils.deleteDirectory(tmp);
        }
        Files.createDirectories(tmp);

        git = new PackageGitManager(tmp, GITEA);
        cli = new CliHelper(tmp);
    }

    @Test
    void createCreatesWorkspaceAndClonesDistribution() throws Exception {
        URI distribution =
                createRepository(
                        "distribution",
                        CliHelper.resourceDirectory("/workspace-add-remove/distribution/initial"));

        CliResult result =
                cli.runCli(
                        tmp,
                        Map.of(),
                        "workspace",
                        "create",
                        "--name",
                        "created-workspace",
                        "--distribution",
                        distribution.toString());

        CliHelper.assertSuccess(result);
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
                cli.runCli(
                        workspace,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "add",
                        "--package",
                        "sample");

        CliHelper.assertSuccess(result);
        assertTrue(Files.isDirectory(workspace.resolve("src/sample/.git")), result.output());
        assertEquals(
                "workspace add remove fixture\n",
                Files.readString(workspace.resolve("src/sample/README.md")));
        assertTrue(
                Files.isRegularFile(workspace.resolve("src/sample/wstool.pkl")), result.output());
    }

    @Test
    void selectResolvesSelectorWithoutOutputOption() throws Exception {
        URI packageRepository = createSamplePackage();
        Path workspace = createWorkspace("select-workspace", packageRepository);
        CliHelper.assertSuccess(
                cli.runCli(
                        workspace,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "add",
                        "--package",
                        "sample"));

        CliResult result =
                cli.runCli(
                        workspace.resolve("src/sample"),
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "select",
                        "(prefix sample)");

        CliHelper.assertSuccess(result);
        assertEquals(
                workspace.resolve("private/prefix/sample/1.0.0").toAbsolutePath() + "\n",
                result.output());
    }

    @Test
    void removeDeletesClonedPackageFromWorkspace() throws Exception {
        URI packageRepository = createSamplePackage();
        Path workspace = createWorkspace("remove-workspace", packageRepository);
        CliResult addResult =
                cli.runCli(
                        workspace,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "add",
                        "--package",
                        "sample");
        CliHelper.assertSuccess(addResult);
        assertTrue(Files.exists(workspace.resolve("src/sample")), addResult.output());

        CliResult removeResult =
                cli.runCli(
                        workspace,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "remove",
                        "--package",
                        "sample");

        CliHelper.assertSuccess(removeResult);
        assertFalse(Files.exists(workspace.resolve("src/sample")), removeResult.output());
    }

    private URI createSamplePackage() throws Exception {
        return createRepository(
                "sample", CliHelper.resourceDirectory("/workspace-add-remove/packages/sample"));
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
                        CliHelper.resourceDirectory("/workspace-add-remove/distribution/updated"));
        Path workspace = tmp.resolve(name);
        CliResult result =
                cli.runCli(
                        tmp,
                        Map.of("PACKAGE_REPOSITORY", packageRepository.toString()),
                        "workspace",
                        "create",
                        "--name",
                        name,
                        "--distribution",
                        distribution.toString());
        CliHelper.assertSuccess(result);
        assertTrue(Files.isDirectory(workspace.resolve("distribution")), result.output());
        return workspace;
    }
}
