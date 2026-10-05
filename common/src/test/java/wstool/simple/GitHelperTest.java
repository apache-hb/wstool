package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import dev.parsick.testcontainers.gitserver.gitea.GiteaVersions;
import io.gitea.ApiClient;
import io.gitea.api.RepositoryApi;
import io.gitea.model.CreateRepoOption;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.git.ImmutableGitRunInput;

@Testcontainers(disabledWithoutDocker = true)
public class GitHelperTest {
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
    void clonesRemoteRepositoryAtRequestedBranch() throws Exception {
        uploadRepository("package");
        URI repository = gitRepoUri("package");
        Path parent = tempDir.resolve("clones");

        new GitHelper(false)
                .clone(
                        ImmutableGitCloneInput.builder()
                                .parent(parent)
                                .name("package")
                                .repository(
                                        ImmutableGitRepositoryLocation.builder()
                                                .repository(repository)
                                                .branch("main")
                                                .username(gitServer.getUserName())
                                                .password(gitServer.getUserPassword())
                                                .build())
                                .build());

        Path clone = parent.resolve("package");
        assertTrue(Files.isDirectory(clone.resolve(".git")));
        assertTrue(Files.readString(clone.resolve("README.md")).contains("fixture"));
        assertTrue(Files.readString(clone.resolve(".git/HEAD")).contains("refs/heads/main"));
    }

    @Test
    void runExecutesGitCommandInRequestedDirectory() throws Exception {
        Path repository = uploadRepository("run-target");

        new GitHelper(false)
                .run(
                        ImmutableGitRunInput.builder()
                                .directory(repository)
                                .arguments(List.of("git", "rev-parse", "--is-inside-work-tree"))
                                .interactive(false)
                                .build());
    }

    @Test
    void runThrowsWhenGitCommandFails() {
        assertThrows(
                RuntimeException.class,
                () ->
                        new GitHelper(false)
                                .run(
                                        ImmutableGitRunInput.builder()
                                                .directory(tempDir)
                                                .arguments(List.of("git", "not-a-real-command"))
                                                .interactive(false)
                                                .build()));
    }

    private URI createRepository(String name) throws Exception {
        giteaApi.createCurrentUserRepo(new CreateRepoOption().name(name)._private(false));
        return gitRepoUri(name);
    }

    private Path uploadRepository(String name) throws Exception {
        URI repository = createRepository(name);
        Path uploadDirectory = tempDir.resolve("uploads").resolve(name);
        Files.createDirectories(uploadDirectory);
        runGit(uploadDirectory, "init", "--initial-branch=main");
        runGit(uploadDirectory, "config", "user.name", gitServer.getUserName());
        runGit(uploadDirectory, "config", "user.email", "git-helper@example.test");
        Files.writeString(
                uploadDirectory.resolve("README.md"), "fixture\n", StandardCharsets.UTF_8);
        runGit(uploadDirectory, "add", "--all");
        runGit(uploadDirectory, "commit", "--message", "Add fixture");
        runGit(uploadDirectory, "remote", "add", "origin", repository.toString());
        runGit(uploadDirectory, "push", "--set-upstream", "origin", "main");
        return uploadDirectory;
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

    private String gitHost() {
        return gitServer.getHost() + ":" + gitServer.getMappedPort(3000);
    }

    private URI gitRepoUri(String repository) {
        return URI.create(
                "http://" + gitHost() + "/" + gitServer.getUserName() + "/" + repository + ".git");
    }
}
