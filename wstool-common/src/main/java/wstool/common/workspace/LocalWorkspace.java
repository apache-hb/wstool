package wstool.common.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.io.file.PathUtils;
import wstool.common.distribution.DistributionConfig;
import wstool.common.distribution.DistributionFactory;
import wstool.common.distribution.DistributionPackage.GitRepository;
import wstool.common.helpers.Asserts;
import wstool.common.manifest.WorkspaceStorage;
import wstool.common.model.PackageDependency;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageConfig;
import wstool.common.packages.PackageFactory;
import wstool.git.GitClient;
import wstool.git.model.GitCloneInput;
import wstool.git.model.GitRepositoryLocation;
import wstool.git.model.ImmutableGitCloneInput;
import wstool.git.model.ImmutableGitRepositoryLocation;

public class LocalWorkspace {
    private static final String DISTRIBUTION = "distribution";
    private static final String SOURCES = "src";
    private static final String CACHE = "private/cache";
    private static final String PREFIX = "private/prefix";
    private static final String BUILD = "private/build";
    private static final String BUILD_SOURCE = "private/build-source";
    private static final String SELECTOR_CACHE = "private/selector-cache";
    private static final String BUILD_CLOSURE_CACHE = "private/build-closure-cache";
    private static final String MANIFEST = "wstool.db";

    private final Path path;
    private final WorkspaceStorage storage;
    private final DistributionFactory distributionFactory;
    private final PackageFactory packageFactory;
    private final GitClient git;

    public LocalWorkspace(
            Path path,
            DistributionFactory distributionFactory,
            PackageFactory packageFactory,
            GitClient git)
            throws Exception {
        this.path = path;
        this.storage = new WorkspaceStorage(path.resolve(MANIFEST));
        this.distributionFactory = distributionFactory;
        this.packageFactory = packageFactory;
        this.git = git;
    }

    public Path path() {
        return path;
    }

    public WorkspaceStorage storage() {
        return storage;
    }

    public void setup(GitRepositoryLocation distribution) throws Exception {
        storage.setup();

        Files.createDirectories(path.resolve(SOURCES));
        Files.createDirectories(path.resolve(CACHE));
        Files.createDirectories(path.resolve(PREFIX));
        Files.createDirectories(path.resolve(BUILD));
        Files.createDirectories(path.resolve(BUILD_SOURCE));
        Files.createDirectories(path.resolve(SELECTOR_CACHE));
        Files.createDirectories(path.resolve(BUILD_CLOSURE_CACHE));

        GitCloneInput input =
                ImmutableGitCloneInput.builder()
                        .parent(path)
                        .name("distribution")
                        .repository(distribution)
                        .build();

        git.clone(input);
    }

    public void refresh() throws Exception {
        DistributionConfig config = loadDistribution();
        var packages = config.packages().stream().map(pkg -> pkg.name()).toList();
        var dependencies =
                cloned().stream().map(this::dependenciesOf).flatMap(List::stream).toList();

        storage.refresh(packages, dependencies);
    }

    public PackageMajorVersion find(String name) throws Exception {
        DistributionConfig config = loadDistribution();
        var packages =
                config.packages().stream()
                        .filter(pkg -> pkg.name().name().equals(name))
                        .map(pkg -> pkg.name())
                        .toList();

        if (packages.isEmpty()) {
            throw new IllegalArgumentException("Package " + name + " not found in distribution");
        }

        if (packages.size() > 1) {
            throw new IllegalArgumentException("Multiple packages found with name " + name);
        }

        return packages.get(0);
    }

    public void clone(PackageMajorVersion name) throws Exception {
        DistributionConfig config = loadDistribution();

        var pkg =
                config.packages().stream()
                        .filter(it -> it.name().equals(name))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Package " + name + " not found in distribution"));

        var repo = (GitRepository) pkg;

        GitRepositoryLocation repository =
                ImmutableGitRepositoryLocation.builder()
                        .branch(repo.branch())
                        .repository(repo.repository())
                        .build();

        GitCloneInput input =
                ImmutableGitCloneInput.builder()
                        .parent(path.resolve(SOURCES))
                        .name(name.name())
                        .repository(repository)
                        .build();

        git.clone(input);
    }

    public void remove(PackageMajorVersion name) throws Exception {
        PathUtils.deleteDirectory(path.resolve(SOURCES).resolve(name.name()));
    }

    public void validate() {
        Asserts.check(Files.exists(path.resolve(SOURCES)), "Missing " + SOURCES + " directory");
        Asserts.check(Files.exists(path.resolve(CACHE)), "Missing " + CACHE + " directory");
        Asserts.check(Files.exists(path.resolve(PREFIX)), "Missing " + PREFIX + " directory");
        Asserts.check(Files.exists(path.resolve(BUILD)), "Missing " + BUILD + " directory");
        Asserts.check(Files.exists(path.resolve(MANIFEST)), "Missing " + MANIFEST + " manifest");
        Asserts.check(
                Files.isDirectory(path.resolve(DISTRIBUTION)),
                "Missing " + DISTRIBUTION + " repository");
    }

    public Path builddir(PackageMajorVersion name) {
        return path.resolve(BUILD).resolve(name.name()).resolve(name.version());
    }

    public Path buildSource(PackageMajorVersion name) {
        return path.resolve(BUILD_SOURCE).resolve(name.name()).resolve(name.version());
    }

    public Path cache() {
        return path.resolve(CACHE);
    }

    public Path prefix(PackageMajorVersion name) {
        return path.resolve(PREFIX).resolve(name.name()).resolve(name.version());
    }

    public Path source(PackageMajorVersion name) {
        return path.resolve(SOURCES).resolve(name.name());
    }

    public Path buildClosureCache(PackageMajorVersion name) {
        return path.resolve(BUILD_CLOSURE_CACHE).resolve(name.name()).resolve(name.version());
    }

    public Path selectorCache(PackageMajorVersion name, String key) {
        return path.resolve(SELECTOR_CACHE)
                .resolve(name.name())
                .resolve(name.version())
                .resolve(key);
    }

    private List<PackageConfig> cloned() throws Exception {
        try (Stream<Path> packages = Files.list(path.resolve(SOURCES))) {
            List<PackageConfig> configs = new ArrayList<>();
            for (Path path : packages.toList()) {
                configs.add(packageFactory.open(path));
            }
            return configs;
        }
    }

    private List<PackageDependency> dependenciesOf(PackageConfig config) {
        List<PackageDependency> dependencies = new ArrayList<>();
        for (var entry : config.dependencies().entrySet()) {
            for (var dependency : entry.getValue()) {
                dependencies.add(new PackageDependency(config.name(), dependency, entry.getKey()));
            }
        }
        return dependencies;
    }

    private DistributionConfig loadDistribution() throws Exception {
        return distributionFactory.open(path.resolve(DISTRIBUTION));
    }

    public static boolean isWorkspace(Path path) {
        return Files.exists(path.resolve(MANIFEST));
    }
}
