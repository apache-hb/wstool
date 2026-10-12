package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;

import wstool.Package;
import wstool.simple.model.PackageMajorVersion;

public class PackageFactory {
    private final WorkspaceFactory workspaceFactory;
    private final PklHelper pklHelper;

    public PackageFactory(WorkspaceFactory workspaceFactory, PklHelper pklHelper) {
        this.workspaceFactory = workspaceFactory;
        this.pklHelper = pklHelper;
    }

    public MaterializedPackage findUp(Path path) throws Exception {
        Workspace workspace = workspaceFactory.findUp(path);
        Path current = path.toAbsolutePath();

        while (current != null && !Files.exists(current.resolve(LocalPackage.CONFIG))) {
            current = current.getParent();
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "No workspace found in current directory or any parent directories.");
        }

        Package config = pklHelper.evaluate(path).as(Package.class);
        PackageMajorVersion pmv = new PackageMajorVersion(config.getPackage().getName(), config.getPackage().getVersion());
        return workspace.find(pmv);
    }
}
