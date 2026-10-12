package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import wstool.Distribution;
import wstool.Package;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.AddDependencyInput;
import wstool.simple.storage.DependencyType;
import wstool.simple.storage.ImmutableAddDependencyInput;

public class Workspace implements AutoCloseable {
    public static final String DATABASE = "wstool.db";
    public static final String DISTRIBUTION = "distribution";

    private final Path path;
    private final WorkspaceStorage storage;
    private final PklHelper pklHelper;

    public Workspace(Path path, PklHelper pklHelper) throws Exception {
        Path db = path.resolve(DATABASE);
        if (Files.notExists(db)) {
            throw new IllegalStateException(
                    "No workspace storage found at: " + db.toAbsolutePath());
        }

        this.path = path;
        this.pklHelper = pklHelper;
        this.storage = new WorkspaceStorage(path, db);
    }

    public void refresh(Distribution distribution) throws Exception {
        List<PackageMajorVersion> pmvs =
                distribution.getPackages().stream()
                        .map(pkg -> new PackageMajorVersion(pkg.getName(), pkg.getVersion()))
                        .toList();

        storage.refresh(path, pmvs);

        for (var pmv : pmvs) {
            LocalPackage it = storage.find(pmv);
            try {
                Package config =
                        pklHelper.evaluate(it.source().resolve("wstool.pkl")).as(Package.class);
                addPackageDependencies(pmv, config.getBuildDependencies(), DependencyType.BUILD);
                addPackageDependencies(pmv, config.getDependencies(), DependencyType.PUBLIC);
                addPackageDependencies(
                        pmv, config.getRuntimeDependencies(), DependencyType.RUNTIME);
                addPackageDependencies(pmv, config.getTestDependencies(), DependencyType.TEST);
                addPackageDependencies(
                        pmv, config.getOverrideDependencies(), DependencyType.OVERRIDE);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public MaterializedPackage find(PackageMajorVersion name) throws Exception {
        return storage.find(name);
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

    private void addPackageDependencies(
            PackageMajorVersion name, Map<String, String> dependencies, DependencyType type)
            throws Exception {
        for (var entry : dependencies.entrySet()) {
            AddDependencyInput input =
                    ImmutableAddDependencyInput.builder()
                            .name(name)
                            .dependency(new PackageMajorVersion(entry.getKey(), entry.getValue()))
                            .type(type)
                            .build();

            storage.addDependency(input);
        }
    }
}
