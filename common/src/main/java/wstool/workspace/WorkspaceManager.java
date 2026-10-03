package wstool.workspace;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceManager {
    public WorkspaceManager() {}

    public LocalWorkspace setupWorkspace(Path pwd, String name) throws Exception {
        Path root = pwd.resolve(name);
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("packages"));
        Files.createDirectories(root.resolve("build"));
        Files.createDirectories(root.resolve("distribution"));
        return openWorkspace(root);
    }

    public LocalWorkspace openWorkspace(Path path) throws Exception {
        return new LocalWorkspace(path);
    }
}
