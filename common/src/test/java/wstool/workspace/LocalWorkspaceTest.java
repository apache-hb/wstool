package wstool.workspace;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import dev.parsick.testcontainers.gitserver.gitea.GiteaVersions;
import io.gitea.ApiClient;
import io.gitea.ApiException;
import io.gitea.api.RepositoryApi;
import io.gitea.model.CreateRepoOption;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LocalWorkspaceTest {
    private static final Path INSPECTION_DIRECTORY = Path.of("build", "local-workspace-test");

    @Container
    private static final GiteaContainer gitServer =
            new GiteaContainer(GiteaVersions.V1_25_4.getDockerImageName())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @TempDir Path tempDir;

    RepositoryApi giteaApi;

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

    private String gitHost() {
        return gitServer.getHost() + ":" + gitServer.getMappedPort(3000);
    }

    private URI gitRepoUri(String repository) {
        return URI.create(
                "http://" + gitHost() + "/" + gitServer.getUserName() + "/" + repository + ".git");
    }

    @Test
    void uploadsAndClonesEverySimplePackage() throws Exception {
        Path fixtures = resourcePath("simple");
        Path output = INSPECTION_DIRECTORY.toAbsolutePath();
        deleteRecursively(output);
        Files.createDirectories(output);

        PklHelper pkl =
                new PklHelper(
                        Map.of(
                                "GIT_ENDPOINT",
                                "http://" + gitHost(),
                                "GIT_USERNAME",
                                gitServer.getUserName(),
                                "AWS_S3_ENDPOINT",
                                "a",
                                "STORAGE_BUCKET",
                                "a"));

        GitHelper git = new GitHelper(gitServer.getUserName(), gitServer.getUserPassword());
        WorkspaceManager manager = new WorkspaceManager(git, pkl);

        String distributionUrl =
                uploadGitRepo(git, fixtures.resolve("distribution"), "distribution");
        uploadGitRepo(git, fixtures.resolve("root"), "root");
        uploadGitRepo(git, fixtures.resolve("middle"), "middle");
        uploadGitRepo(git, fixtures.resolve("upper"), "upper");

        LocalWorkspace workspace =
                manager.setupWorkspace(output, "simple", URI.create(distributionUrl), "master");

        for (String packageName : List.of("root", "middle", "upper")) {
            workspace.clone(packageName);

            Path clonedPackage = output.resolve("simple/src").resolve(packageName);
            assertTrue(Files.isDirectory(clonedPackage), "Missing clone for " + packageName);
            assertTrue(
                    Files.exists(clonedPackage.resolve("wstool.pkl")),
                    "Missing wstool config for " + packageName);
        }
    }

    private String uploadGitRepo(GitHelper git, Path path, String name) throws Exception {
        createRepository(name);

        Path uploadDirectory = tempDir.resolve("uploads").resolve(name);
        copyDirectory(path, uploadDirectory);
        runGit(git, uploadDirectory, "init", "--initial-branch=master");
        runGit(git, uploadDirectory, "config", "user.name", gitServer.getUserName());
        runGit(git, uploadDirectory, "config", "user.email", "local-workspace@example.test");
        runGit(git, uploadDirectory, "add", "--all");
        runGit(git, uploadDirectory, "commit", "--message", "Add " + name + " fixture");
        runGit(git, uploadDirectory, "remote", "add", "origin", gitRepoUri(name).toString());
        runGit(git, uploadDirectory, "push", "--set-upstream", "origin", "master");

        return gitRepoUri(name).toString();
    }

    private static Path resourcePath(String name) throws Exception {
        URL resource = LocalWorkspaceTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }

    private void createRepository(String name) throws ApiException {
        giteaApi.createCurrentUserRepo(new CreateRepoOption().name(name)._private(false));
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

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(directory)) {
            for (Path entry : entries.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static void runGit(GitHelper git, Path directory, String... arguments)
            throws Exception {
        git.run(directory, arguments);
    }
}
