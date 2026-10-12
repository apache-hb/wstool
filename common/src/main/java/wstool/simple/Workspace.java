package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import wstool.simple.model.PackageMajorVersion;

public class Workspace implements AutoCloseable {
    private final Path path;
    private final WorkspaceStorage storage;

    public Workspace(Path path) throws Exception {
        Path db = path.resolve("wstool.db");
        if (Files.notExists(db)) {
            throw new IllegalStateException("No workspace storage found at: " + db.toAbsolutePath());
        }

        this.path = path;
        this.storage = new WorkspaceStorage(db);
    }

    public boolean refresh() throws Exception {
        return false;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws Exception {
        storage.close();
    }

    public WorkspaceStorage storage() {
        return storage;
    }
}
