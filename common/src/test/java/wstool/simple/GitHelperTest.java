package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.git.ImmutableGitRunInput;

public class GitHelperTest {
    @TempDir Path tempDir;

    @Test
    void clonesLocalRepositoryAtRequestedBranch() throws Exception {
        Path repository = createRepository("main");
        Path parent = tempDir.resolve("clones");

        new GitHelper(false)
                .clone(
                        ImmutableGitCloneInput.builder()
                                .parent(parent)
                                .name("package")
                                .repository(
                                        ImmutableGitRepositoryLocation.builder()
                                                .repository(repository.toUri())
                                                .branch("main")
                                                .build())
                                .build());

        Path clone = parent.resolve("package");
        assertTrue(Files.isDirectory(clone.resolve(".git")));
        assertTrue(Files.readString(clone.resolve("README.md")).contains("fixture"));
    }

    @Test
    void runExecutesGitCommandInRequestedDirectory() throws Exception {
        Path repository = createRepository("main");

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

    private Path createRepository(String branch) throws Exception {
        Path repository = tempDir.resolve("source-" + branch);
        Files.createDirectories(repository);
        runGit(repository, "init", "--initial-branch=" + branch);
        runGit(repository, "config", "user.name", "Helper Test");
        runGit(repository, "config", "user.email", "helper-test@example.test");
        Files.writeString(repository.resolve("README.md"), "fixture\n", StandardCharsets.UTF_8);
        runGit(repository, "add", "--all");
        runGit(repository, "commit", "--message", "Add fixture");
        return repository;
    }

    private static void runGit(Path directory, String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        byte[] output = process.getInputStream().readAllBytes();
        int status = process.waitFor();
        if (status != 0) {
            throw new IOException(
                    "Git command failed: " + new String(output, StandardCharsets.UTF_8));
        }
    }
}
