package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.git.ImmutableGitRunInput;
import wstool.test.components.PackageGitManager;

@Testcontainers(disabledWithoutDocker = true)
public class GitHelperTest {
    @Container
    private static final GiteaContainer gitea = PackageGitManager.container();

    @TempDir
    Path tmp;

    private PackageGitManager gitManager;

    @BeforeEach
    void beforeEach() {
        gitManager = new PackageGitManager(tmp, gitea);
    }

    @Test
    void clonesRemoteRepositoryAtRequestedBranch() throws Exception {
        uploadRepository("package");
        URI repository = gitManager.repositoryUri("package");
        Path parent = tmp.resolve("clones");

        new GitHelper(false)
                .clone(
                        ImmutableGitCloneInput.builder()
                                .parent(parent)
                                .name("package")
                                .repository(
                                        ImmutableGitRepositoryLocation.builder()
                                                .repository(repository)
                                                .branch("main")
                                                .username(gitea.getUserName())
                                                .password(gitea.getUserPassword())
                                                .build())
                                .build());

        Path clone = parent.resolve("package");
        assertTrue(Files.isDirectory(clone.resolve(".git")));
        assertEquals("fixture\n", Files.readString(clone.resolve("README.md")));
        assertEquals("ref: refs/heads/main\n", Files.readString(clone.resolve(".git/HEAD")));
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
                                                .directory(tmp)
                                                .arguments(List.of("git", "not-a-real-command"))
                                                .interactive(false)
                                                .build()));
    }

    private Path uploadRepository(String name) throws Exception {
        URI repository = gitManager.setup(name);
        Path uploadDirectory = tmp.resolve("uploads").resolve(name);
        Files.createDirectories(uploadDirectory);
        runGit(uploadDirectory, "init", "--initial-branch=main");
        runGit(uploadDirectory, "config", "user.name", gitea.getUserName());
        runGit(uploadDirectory, "config", "user.email", "git-helper@example.test");
        copyResource("git-helper/repository/README.md", uploadDirectory.resolve("README.md"));
        runGit(uploadDirectory, "add", "--all");
        runGit(uploadDirectory, "commit", "--message", "Add fixture");
        runGit(uploadDirectory, "remote", "add", "origin", repository.toString());
        runGit(uploadDirectory, "push", "--set-upstream", "origin", "main");
        return uploadDirectory;
    }

    private void copyResource(String name, Path destination) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + name);
            }
            Files.copy(input, destination);
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
                                .username(gitea.getUserName())
                                .password(gitea.getUserPassword())
                                .build());
    }
}
