package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;
import wstool.simple.git.GitCloneInput;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.workspace.CreateWorkspaceInput;

public class WorkspaceHelper {
    private static final String CONFIG_FILE = "wstool.db";

    private final GitHelper git;
    private final StorageHelper storage;

    public WorkspaceHelper(GitHelper git, StorageHelper storage) {
        this.git = git;
        this.storage = storage;
    }

    public void createWorkspace(CreateWorkspaceInput input) throws Exception {
        Path workspace = input.parent().resolve(input.name());

        Files.createDirectories(workspace);

        // Folder where locally cloned packages are placed
        Files.createDirectories(workspace.resolve("src"));

        Files.createDirectories(workspace.resolve("private"));

        // Folder where remote artifacts are cached
        Files.createDirectories(workspace.resolve("private/cache"));

        // Locally build artifacts
        Files.createDirectories(workspace.resolve("private/artifacts"));

        // Intermediate build directories
        Files.createDirectories(workspace.resolve("private/build"));

        // The distribution config
        GitCloneInput clone =
                ImmutableGitCloneInput.builder()
                        .parent(workspace)
                        .name("distribution")
                        .repository(input.distribution())
                        .build();

        git.clone(clone);

        storage.createWorkspaceStorage(workspace.resolve(CONFIG_FILE));
    }

    public Path findWorkspaceUp(Path path) {
        Path current = path.toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(CONFIG_FILE))) {
            current = current.getParent();
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "No workspace found in current directory or any parent directories.");
        }

        return current;
    }
}
