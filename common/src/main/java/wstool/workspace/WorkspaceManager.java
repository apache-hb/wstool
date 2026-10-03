package wstool.workspace;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceManager {
    private final GitHelper git;
    private final PklHelper pkl;

    public WorkspaceManager(GitHelper git, PklHelper pkl) {
        this.git = git;
        this.pkl = pkl;
    }

    public LocalWorkspace setupWorkspace(Path pwd, String name, URI distribution, String branch)
            throws Exception {
        Path root = pwd.resolve(name);

        Files.createDirectories(root);
        git.clone(root, distribution, branch, "distribution");
        Files.createDirectories(root.resolve("packages"));
        Files.createDirectories(root.resolve("build"));

        return openWorkspace(root);
    }

    public LocalWorkspace openWorkspace(Path path) throws Exception {
        return new LocalWorkspace(git, pkl, path);
    }
}
