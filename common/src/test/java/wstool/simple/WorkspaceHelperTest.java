package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.workspace.ImmutableCreateWorkspaceInput;

public class WorkspaceHelperTest {
    @TempDir Path tempDir;

    @Test
    void createsWorkspaceDirectoriesClonesDistributionAndInitializesStorage() throws Exception {
        Path distribution = createRepository();
        Path parent = tempDir.resolve("workspaces");
        WorkspaceHelper helper = new WorkspaceHelper(new GitHelper(false), new StorageHelper());

        helper.createWorkspace(
                ImmutableCreateWorkspaceInput.builder()
                        .parent(parent)
                        .name("demo")
                        .distribution(
                                ImmutableGitRepositoryLocation.builder()
                                        .repository(distribution.toUri())
                                        .branch("main")
                                        .build())
                        .build());

        Path workspace = parent.resolve("demo");
        for (String directory :
                List.of("src", "private/cache", "private/artifacts", "private/build")) {
            assertTrue(Files.isDirectory(workspace.resolve(directory)), "Missing " + directory);
        }
        assertTrue(Files.isDirectory(workspace.resolve("distribution/.git")));
        assertTrue(Files.exists(workspace.resolve("wstool.db")));
        assertEquals(workspace, helper.findWorkspaceUp(workspace.resolve("src")));
    }

    @Test
    void findWorkspaceUpThrowsWhenNoWorkspaceExists() {
        WorkspaceHelper helper = new WorkspaceHelper(new GitHelper(false), new StorageHelper());

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> helper.findWorkspaceUp(tempDir));
        assertTrue(error.getMessage().contains("No workspace found"));
    }

    private Path createRepository() throws Exception {
        Path repository = tempDir.resolve("distribution-source");
        Files.createDirectories(repository);
        runGit(repository, "init", "--initial-branch=main");
        runGit(repository, "config", "user.name", "Helper Test");
        runGit(repository, "config", "user.email", "helper-test@example.test");
        Files.writeString(repository.resolve("distribution.pkl"), "name = \"fixture\"\n");
        runGit(repository, "add", "--all");
        runGit(repository, "commit", "--message", "Add distribution fixture");
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
