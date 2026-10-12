package wstool.simple;

import java.nio.file.Path;
import java.util.List;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.AddPackageInput;
import wstool.simple.storage.ImmutableAddPackageInput;

public class StorageHelper {
    public void createWorkspaceStorage(Path path) throws Exception {
        try (var connection = new WorkspaceStorage(workspace(path), path)) {
            connection.setup();
        }
    }

    public void addPackageToWorkspaceStorage(
            Path path, String name, String version, String source, boolean cloned)
            throws Exception {

        AddPackageInput input =
                ImmutableAddPackageInput.builder()
                        .name(new PackageMajorVersion(name, version))
                        .source(Path.of(source))
                        .cloned(cloned)
                        .build();

        try (var connection = new WorkspaceStorage(workspace(path), path)) {
            connection.addPackage(input);
        }
    }

    public List<PackageMajorVersion> removeClonedPackagesFromWorkspaceStorage(
            Path path, String name) throws Exception {
        try (var connection = new WorkspaceStorage(workspace(path), path)) {
            return connection.removeClonedPackages(name);
        }
    }

    public void refreshDistributionPackages(
            Path path, Path workspace, List<PackageMajorVersion> distributionPackages)
            throws Exception {
        try (var connection = new WorkspaceStorage(workspace, path)) {
            connection.refresh(workspace, distributionPackages);
        }
    }

    private Path workspace(Path database) {
        return database.toAbsolutePath().getParent();
    }
}
