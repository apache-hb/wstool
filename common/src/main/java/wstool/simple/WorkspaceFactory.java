package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;

import wstool.simple.distribution.DistributionFactory;

public class WorkspaceFactory {
    private final PklHelper pklHelper;
    private final DistributionFactory distributionFactory;
    private final PackageFactory packageFactory;

    public WorkspaceFactory(PklHelper pklHelper, DistributionFactory distributionFactory, PackageFactory packageFactory) {
        this.pklHelper = pklHelper;
        this.distributionFactory = distributionFactory;
        this.packageFactory = packageFactory;
    }

    public Workspace open(Path path) throws Exception {
        Workspace workspace = new Workspace(path, distributionFactory, packageFactory, pklHelper);
        workspace.refresh();
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
