package wstool.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.core.ModuleSource;
import wstool.Distribution.DistributionConfig;
import wstool.Distribution.GitRepository;
import wstool.Distribution.PackageSource;
import wstool.common.model.PackageMajorVersion;

public class LocalWorkspace {
    private final Path root;
    private final DistributionConfig distribution;
    private final WorkspaceSchema storage;
    private final Map<String, LocalPackage> packages;

    public LocalWorkspace(Path root) throws Exception {
        this.root = Objects.requireNonNull(root);
        try (ConfigEvaluator evaluator = ConfigEvaluator.preconfigured()) {
            Path path = root.resolve("distribution").resolve("distribution.pkl");
            Config config = evaluator.evaluate(ModuleSource.path(path));
            this.distribution = config.get("distribution").as(DistributionConfig.class);
        }
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
                            root.resolve("src").resolve(name), root.resolve("build").resolve(name));
            storage.addPackage(new PackageMajorVersion(name, ""), cloned.source(), true);
            this.packages.put(name, cloned);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported package source type: " + source.getClass().getName());
        }
    }

    private void gitClone(GitRepository repository) throws IOException, InterruptedException {
        ProcessBuilder pb =
                new ProcessBuilder(
                        "git",
                        "clone",
                        repository.getRepo().toString(),
                        "--branch",
                        repository.getBranch(),
                        repository.getName());

        pb.directory(root.resolve("src").toFile());
        Process process = pb.start();
        int status = process.waitFor();
        if (status != 0) {
            throw new IOException("Failed to clone repository: " + repository);
        }
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
