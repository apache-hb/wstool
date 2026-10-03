package wstool.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class WorkspaceManager {
    private final Path pwd;

    public WorkspaceManager(Path pwd) {
        this.pwd = Objects.requireNonNull(pwd);
    }

    public void setupWorkspace(String name) throws IOException {
        Path root = pwd.resolve(name);
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("packages"));
        Files.createDirectories(root.resolve("build"));
        Files.createDirectories(root.resolve("distribution"));
    }
}
