package wstool.workspace;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.pkl.config.java.Config;
import wstool.Distribution.DistributionConfig;
import wstool.Distribution.GitRepository;
import wstool.Distribution.PackageSource;
import wstool.common.model.PackageMajorVersion;

public class LocalWorkspace {
    private final GitHelper git;
    private final PklHelper pkl;

    private final Path root;
    private final DistributionConfig distribution;
    private final WorkspaceSchema storage;
    private final Map<String, LocalPackage> packages;

    public LocalWorkspace(GitHelper git, PklHelper pkl, Path root) throws Exception {
        Path path = root.resolve("distribution").resolve("distribution.pkl");
        Config config = pkl.evaluate(path);

        this.git = git;
        this.pkl = pkl;
        this.root = Objects.requireNonNull(root);
        this.distribution = config.get("distribution").as(DistributionConfig.class);
        this.storage = new WorkspaceSchema(root.resolve("workspace.db"));
        this.packages = new HashMap<>();
    }

    public Path root() {
        return root;
    }

    public DistributionConfig distributionConfig() {
        return distribution;
    }

    public void clone(String name) throws Exception {
        PackageSource source = findPackageSource(name);
        if (source instanceof GitRepository repository) {
            gitClone(repository);

            LocalPackage cloned =
                    new LocalPackage(
                            sourcedir().resolve(name), root.resolve("build").resolve(name), pkl);
            storage.addPackage(
                    new PackageMajorVersion(name, ""), cloned.source().relativize(root), true);
            this.packages.put(name, cloned);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported package source type: " + source.getClass().getName());
        }
    }

    private Path sourcedir() {
        return root.resolve("src");
    }

    private void gitClone(GitRepository repository) throws Exception {
        git.clone(
                root.resolve("src"),
                repository.getRepo(),
                repository.getBranch(),
                repository.getName());
    }

    private PackageSource findPackageSource(String name) {
        for (PackageSource source : distribution.getPackages()) {
            if (source.getName().equalsIgnoreCase(name)) {
                return source;
            }
        }

        throw new IllegalArgumentException("Package source not found: " + name);
    }
}
