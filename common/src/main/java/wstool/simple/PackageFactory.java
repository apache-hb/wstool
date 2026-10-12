package wstool.simple;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import wstool.Package;
import wstool.simple.model.PackageMajorVersion;

@Singleton
public class PackageFactory {
    private final PklHelper pklHelper;

    @Inject
    public PackageFactory(PklHelper pklHelper) {
        this.pklHelper = pklHelper;
    }

    public MaterializedPackage findUp(Workspace workspace, Path path) throws Exception {
        Path current = path.toAbsolutePath();

        while (current != null && !Files.exists(current.resolve(LocalPackage.CONFIG))) {
            current = current.getParent();
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "No workspace found in current directory or any parent directories.");
        }

        return open(workspace, current);
    }

    public MaterializedPackage open(Workspace workspace, Path path) throws Exception {
        Package config = pklHelper.evaluate(path.resolve(LocalPackage.CONFIG)).as(Package.class);
        PackageMajorVersion pmv =
                new PackageMajorVersion(
                        config.getPackage().getName(), config.getPackage().getVersion());
        return workspace.find(pmv);
    }
}
