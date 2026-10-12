package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import wstool.Distribution;
import wstool.Package;
import wstool.simple.distribution.AnyDistribution;
import wstool.simple.distribution.DistributionFactory;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.AddDependencyInput;
import wstool.simple.storage.DependencyType;
import wstool.simple.storage.ImmutableAddDependencyInput;

public class Workspace implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger(Workspace.class);

    public static final String DATABASE = "wstool.db";
    public static final String DISTRIBUTION = "distribution";

    private final Path path;
    private final WorkspaceStorage storage;
    private final DistributionFactory distributionFactory;
    private final PackageFactory packageFactory;
    private final PklHelper pklHelper;

    public Workspace(Path path, DistributionFactory distributionFactory, PackageFactory packageFactory, PklHelper pklHelper) throws Exception {
        Path db = path.resolve(DATABASE);
        if (Files.notExists(db)) {
            throw new IllegalStateException(
                    "No workspace storage found at: " + db.toAbsolutePath());
        }

        this.path = path;
        this.distributionFactory = distributionFactory;
        this.packageFactory = packageFactory;
        this.pklHelper = pklHelper;
        this.storage = new WorkspaceStorage(path, db);
    }

    public void refresh() throws Exception {
        try {
            storage.refresh(path, distributionConfig().packages());
        } catch (Exception e) {
            LOGGER.info("Skipped distribution refresh", e);
        }
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

    public void clone(String name, String version) throws Exception {
        if (version == null) {
            clone(storage.find(name));
        } else {
            clone(new PackageMajorVersion(name, version));
        }
    }

    public void clone(PackageMajorVersion name) throws Exception {
        clone(storage.find(name));
    }

    private void clone(MaterializedPackage pkg) throws Exception {

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

    private AnyDistribution distributionConfig() {
        return distributionFactory.open(path.resolve(DISTRIBUTION));
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
