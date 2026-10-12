package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.workspace.ImmutableAddPackageToWorkspaceInput;
import wstool.simple.workspace.ImmutableCreateWorkspaceInput;
import wstool.test.components.PackageGitManager;

@Testcontainers(disabledWithoutDocker = true)
public class WorkspaceHelperTest {
    @Container private static final GiteaContainer gitServer = PackageGitManager.container();

    @TempDir Path tempDir;

    private PackageGitManager gitManager;
    private GitHelper git;
    private StorageHelper storage;
    @AutoClose private PklHelper pkl;
    private WorkspaceHelper helper;

    @BeforeEach
    void beforeEach() {
        gitManager = new PackageGitManager(tempDir, gitServer);
        git = new GitHelper(false);
        storage = new StorageHelper();
        pkl = new PklHelper(null);
        helper = workspaceHelper(git, storage, pkl);
    }

    @Test
    void createsWorkspaceDirectoriesClonesDistributionAndInitializesStorage() throws Exception {
        URI distribution = uploadRepository("simple/distribution", "workspace-distribution");
        Path parent = tempDir.resolve("workspaces");
        helper.createWorkspace(
                ImmutableCreateWorkspaceInput.builder()
                        .parent(parent)
                        .name("demo")
                        .distribution(
                                ImmutableGitRepositoryLocation.builder()
                                        .repository(distribution)
                                        .branch("master")
                                        .build())
                        .build());

        Path workspace = parent.resolve("demo");
        for (String directory :
                List.of("src", "private/cache", "private/prefix", "private/build")) {
            assertTrue(Files.isDirectory(workspace.resolve(directory)), "Missing " + directory);
        }
        assertTrue(Files.isDirectory(workspace.resolve("distribution/.git")));
        assertTrue(Files.exists(workspace.resolve("wstool.db")));
        assertEquals(workspace, helper.findWorkspaceUp(workspace.resolve("src")));
    }

    @Test
    void findWorkspaceUpThrowsWhenNoWorkspaceExists() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> helper.findWorkspaceUp(tempDir));
        assertTrue(error.getMessage().contains("No workspace found"));
    }

    @Test
    void addPackageToWorkspaceClonesPackageAndRecordsItInStorage() throws Exception {
        URI packageRepository = uploadRepository("add-package/package", "workspace-package");
        URI distributionRepository =
                uploadRepository("add-package/distribution", "package-distribution");

        Path parent = tempDir.resolve("workspaces");
        Path workspace = parent.resolve("demo");
        try (PklHelper pkl =
                new PklHelper(Map.of("PACKAGE_GIT_REPOSITORY", packageRepository.toString()))) {
            WorkspaceHelper helper = workspaceHelper(new GitHelper(false), storage, pkl);
            helper.createWorkspace(
                    ImmutableCreateWorkspaceInput.builder()
                            .parent(parent)
                            .name("demo")
                            .distribution(
                                    ImmutableGitRepositoryLocation.builder()
                                            .repository(distributionRepository)
                                            .branch("master")
                                            .build())
                            .build());

            helper.addPackageToWorkspace(
                    ImmutableAddPackageToWorkspaceInput.builder()
                            .workspace(workspace)
                            .name("root")
                            .version("1.0")
                            .build());
        }

        Path clonedPackage = workspace.resolve("src/root");
        assertTrue(Files.isDirectory(clonedPackage.resolve(".git")));
        assertEquals("package fixture\n", Files.readString(clonedPackage.resolve("README.md")));

        try (Connection connection =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + workspace.resolve("wstool.db"));
                var statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT name, version, source, cloned FROM packages")) {
            assertTrue(result.next());
            assertEquals("root", result.getString("name"));
            assertEquals("1.0", result.getString("version"));
            assertEquals(workspace.resolve("src/root").toString(), result.getString("source"));
            assertEquals("1", result.getString("cloned"));
            assertFalse(result.next(), "Expected exactly one stored package");
        }
    }

    private WorkspaceHelper workspaceHelper(GitHelper git, StorageHelper storage, PklHelper pkl) {
        DistributionArchiveHelper archives = new DistributionArchiveHelper(new ArchiveHelper());
        PackageMapper mapper = new PackageMapper();
        PackageSourceHelper sources = new PackageSourceHelper(archives, git, mapper);
        return new WorkspaceHelper(
                git,
                storage,
                new DistributionHelper(pkl),
                new PackageHelper(pkl),
                sources,
                new ExternalPackageSourceHelper(archives, sources, new ExternalFilesHelper(), git));
    }

    private URI uploadRepository(String fixture, String name) throws Exception {
        URI repository = gitManager.create(name);
        gitManager.uploadResources(name, fixture);
        return repository;
    }
}
