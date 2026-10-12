package wstool.simple;

import java.nio.file.Path;

public class WorkspaceFactory {
    private final PklHelper pklHelper;

    public WorkspaceFactory(PklHelper pklHelper) {
        this.pklHelper = pklHelper;
    }

    public Workspace open(Path path) throws Exception {
        return new Workspace(path, pklHelper);
    }
}
