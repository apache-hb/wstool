package wstool.simple;

import java.nio.file.Path;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.AddPackageInput;
import wstool.simple.storage.ImmutableAddPackageInput;

public class StorageHelper {
    public void createWorkspaceStorage(Path path) throws Exception {
        try (var connection = new WorkspaceStorage(path)) {
            connection.setup();
        }
    }

    public void addPackageToWorkspaceStorage(
            Path path,
            String name,
            String version,
            String source,
            String prefix,
            String build,
            boolean cloned)
            throws Exception {

        AddPackageInput input =
                ImmutableAddPackageInput.builder()
                        .name(new PackageMajorVersion(name, version))
                        .source(Path.of(source))
                        .prefix(Path.of(prefix))
                        .build(Path.of(build))
                        .cloned(cloned)
                        .build();

        try (var connection = new WorkspaceStorage(path)) {
            connection.addPackage(input);
        }
    }
}
