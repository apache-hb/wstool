package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceFactory {
    private final PklHelper pklHelper;

    public WorkspaceFactory(PklHelper pklHelper) {
        this.pklHelper = pklHelper;
    }

    public Workspace open(Path path) throws Exception {
        Workspace workspace = new Workspace(path, pklHelper);

        return workspace;
    }

    public Workspace findUp(Path path) throws Exception {
        Path current = path.toAbsolutePath();

        while (current != null && !Files.exists(current.resolve(Workspace.DATABASE))) {
            current = current.getParent();
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "No workspace found in current directory or any parent directories.");
        }

        return open(current);
    }
}
