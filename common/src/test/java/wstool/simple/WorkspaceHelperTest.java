package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import dev.parsick.testcontainers.gitserver.gitea.GiteaVersions;
import io.gitea.ApiClient;
import io.gitea.api.RepositoryApi;
import io.gitea.model.CreateRepoOption;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.git.ImmutableGitRunInput;
import wstool.simple.workspace.ImmutableAddPackageToWorkspaceInput;
import wstool.simple.workspace.ImmutableCreateWorkspaceInput;

@Testcontainers(disabledWithoutDocker = true)
public class WorkspaceHelperTest {
    @Container
    private static final GiteaContainer gitServer =
            new GiteaContainer(GiteaVersions.V1_25_4.getDockerImageName())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @TempDir Path tempDir;

    private RepositoryApi giteaApi;

    @BeforeEach
    void beforeEach() {
        ApiClient apiClient =
                new ApiClient()
                        .setBasePath(
                                "http://"
                                        + gitServer.getHost()
                                        + ":"
                                        + gitServer.getMappedPort(3000)
                                        + "/api/v1");
        apiClient.setUsername(gitServer.getUserName());
        apiClient.setPassword(gitServer.getUserPassword());
        giteaApi = new RepositoryApi(apiClient);
    }

    @Test
    void createsWorkspaceDirectoriesClonesDistributionAndInitializesStorage() throws Exception {
        URI distribution = uploadRepository("simple/distribution", "workspace-distribution");
        Path parent = tempDir.resolve("workspaces");
        WorkspaceHelper helper =
                new WorkspaceHelper(
                        new GitHelper(false),
                        new StorageHelper(),
                        new DistributionHelper(new PklHelper(null)));

        helper.createWorkspace(
                ImmutableCreateWorkspaceInput.builder()
                        .parent(parent)
                        .name("demo")
                        .distribution(
                                ImmutableGitRepositoryLocation.builder()
                                        .repository(distribution)
                                        .branch("main")
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
        WorkspaceHelper helper =
                new WorkspaceHelper(
                        new GitHelper(false),
                        new StorageHelper(),
                        new DistributionHelper(new PklHelper(null)));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> helper.findWorkspaceUp(tempDir));
        assertTrue(error.getMessage().contains("No workspace found"));
    }

    @Test
    void addPackageToWorkspaceClonesPackageAndRecordsItInStorage() throws Exception {
        URI packageRepository =
                uploadRepository("add-package/package", "workspace-package");
        URI distributionRepository =
                uploadRepository("add-package/distribution", "package-distribution");

        Path parent = tempDir.resolve("workspaces");
        Path workspace = parent.resolve("demo");
        StorageHelper storage = new StorageHelper();
        try (PklHelper pkl =
                new PklHelper(
                        Map.of("PACKAGE_GIT_REPOSITORY", packageRepository.toString()))) {
            WorkspaceHelper helper =
                    new WorkspaceHelper(new GitHelper(false), storage, new DistributionHelper(pkl));
            helper.createWorkspace(
                    ImmutableCreateWorkspaceInput.builder()
                            .parent(parent)
                            .name("demo")
                            .distribution(
                                    ImmutableGitRepositoryLocation.builder()
                                            .repository(distributionRepository)
                                            .branch("main")
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
                        DriverManager.getConnection("jdbc:sqlite:" + workspace.resolve("wstool.db"));
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT * FROM packages")) {
            assertTrue(result.next());
            assertEquals("root", result.getString("name"));
            assertEquals("1.0", result.getString("version"));
            assertEquals(workspace.resolve("src/root").toString(), result.getString("source"));
            assertEquals(
                    workspace.resolve("private/prefix/root").toString(), result.getString("prefix"));
            assertEquals(
                    workspace.resolve("private/build/root").toString(), result.getString("build"));
            assertEquals("1", result.getString("cloned"));
            assertFalse(result.next(), "Expected exactly one stored package");
        }
    }

    private URI uploadRepository(String fixture, String name) throws Exception {
        giteaApi.createCurrentUserRepo(new CreateRepoOption().name(name)._private(false));

        Path uploadDirectory = tempDir.resolve("uploads").resolve(name);
        copyDirectory(resourcePath(fixture), uploadDirectory);
        runGit(uploadDirectory, "init", "--initial-branch=main");
        runGit(uploadDirectory, "config", "user.name", gitServer.getUserName());
        runGit(uploadDirectory, "config", "user.email", "workspace-helper@example.test");
        runGit(uploadDirectory, "add", "--all");
        runGit(uploadDirectory, "commit", "--message", "Add " + name + " fixture");
        runGit(uploadDirectory, "remote", "add", "origin", gitRepoUri(name).toString());
        runGit(uploadDirectory, "push", "--set-upstream", "origin", "main");
        return gitRepoUri(name);
    }

    private String gitHost() {
        return gitServer.getHost() + ":" + gitServer.getMappedPort(3000);
    }

    private URI gitRepoUri(String repository) {
        return URI.create(
                "http://" + gitHost() + "/" + gitServer.getUserName() + "/" + repository + ".git");
    }

    private static Path resourcePath(String name) throws Exception {
        URL resource = WorkspaceHelperTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        try (Stream<Path> entries = Files.walk(source)) {
            for (Path entry : entries.toList()) {
                Path target = destination.resolve(source.relativize(entry));
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        new GitHelper(false)
                .run(
                        ImmutableGitRunInput.builder()
                                .directory(directory)
                                .arguments(command)
                                .interactive(false)
                                .username(gitServer.getUserName())
                                .password(gitServer.getUserPassword())
                                .build());
    }
}
