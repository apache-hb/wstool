package wstool.simple;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import wstool.Distribution;
import wstool.Distribution.Archive;
import wstool.Distribution.Folder;
import wstool.Distribution.GitRepository;
import wstool.Distribution.PackageSource;
import wstool.Package;
import wstool.simple.git.GitRepositoryLocation;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.model.PackageMajorVersion;

/** Resolves package dependency selectors and assembles their installation prefixes as a sysroot. */
public class DependencyResolver {
    private static final String CONFIG_FILE = "wstool.pkl";
    private static final String SYSROOT_DIRECTORY = ".wstool/sysroots";

    private final GitHelper git;
    private final PklHelper pklHelper;
    private final PackageSourceHelper packageSourceHelper;
    private final DistributionArchiveHelper distributionArchiveHelper;
    private final PackageMapper packageMapper;

    public DependencyResolver(
            GitHelper gitHelper,
            PklHelper pklHelper,
            PackageSourceHelper packageSourceHelper,
            DistributionArchiveHelper distributionArchiveHelper,
            PackageMapper packageMapper) {
        this.git = gitHelper;
        this.pklHelper = pklHelper;
        this.packageSourceHelper = packageSourceHelper;
        this.distributionArchiveHelper = distributionArchiveHelper;
        this.packageMapper = packageMapper;
    }

    public Path createClosure(
            WorkspaceHelper workspace, Path workspaceRoot, String packageName, String closure) {
        return createClosure(workspace, workspaceRoot, packageName, closure, null);
    }

    public Path createClosure(
            WorkspaceHelper workspace,
            Path workspaceRoot,
            String packageName,
            String closure,
            Path output) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Selector selector = parseSelector(packageName, closure);
        LocalPackage root = findRoot(indexPackages(workspace, workspaceRoot), selector.root());
        LinkedHashMap<PackageMajorVersion, LocalPackage> selected =
                resolvePackages(workspace, workspaceRoot, packageName, closure);
        Path sysroot =
                output == null
                        ? workspaceRoot
                                .resolve(SYSROOT_DIRECTORY)
                                .resolve(root.version().name() + "@" + root.version().version())
                                .resolve(selector.closure())
                        : output.toAbsolutePath().normalize();
        try {
            rebuildSysroot(sysroot, selected.values());
            return sysroot;
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(
                    "Unable to create " + selector.closure() + " sysroot for " + root.version(), e);
        }
    }

    /** Returns the package prefixes required by a closure, in dependency order. */
    public LinkedHashMap<PackageMajorVersion, LocalPackage> resolvePackages(
            WorkspaceHelper workspace, Path workspaceRoot, String packageName, String closure) {
        return resolvePackages(workspace, workspaceRoot, packageName, closure, null);
    }

    /**
     * Resolves missing dependency manifests from the distribution and caches them in the workspace.
     */
    public LinkedHashMap<PackageMajorVersion, LocalPackage> resolvePackages(
            WorkspaceHelper workspace,
            Path workspaceRoot,
            String packageName,
            String closure,
            Distribution distribution) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Selector selector = parseSelector(packageName, closure);
        Map<PackageMajorVersion, LocalPackage> packages = indexPackages(workspace, workspaceRoot);
        LocalPackage root = findRoot(packages, selector.root());
        try {
            Package rootConfig = readConfig(root.config());
            Map<PackageMajorVersion, Package> configs = new LinkedHashMap<>();
            configs.put(root.version(), rootConfig);
            return resolveDependencies(
                    selector, rootConfig, packages, configs, workspaceRoot, distribution);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(
                    "Unable to resolve dependencies for " + root.version(), e);
        }
    }

    /** Copies a closure into a mountable directory, resolving host sysroot symlinks. */
    public void materializeClosure(Path destination, Iterable<LocalPackage> packages)
            throws IOException {
        deleteTree(destination);
        Files.createDirectories(destination);
        for (LocalPackage pkg : packages) {
            if (!Files.isDirectory(pkg.prefix())) {
                throw new IOException(
                        "Package artifacts are missing for "
                                + pkg.version()
                                + " at "
                                + pkg.prefix());
            }
            copyTree(pkg.prefix(), destination);
        }
    }

    private void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(destination.resolve(source.relativize(directory)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Path target = destination.resolve(source.relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.copy(
                                file,
                                target,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private LinkedHashMap<PackageMajorVersion, LocalPackage> resolveDependencies(
            Selector selector,
            Package rootConfig,
            Map<PackageMajorVersion, LocalPackage> packages,
            Map<PackageMajorVersion, Package> configs,
            Path workspaceRoot,
            Distribution distribution)
            throws Exception {
        LinkedHashMap<PackageMajorVersion, LocalPackage> selected = new LinkedHashMap<>();
        ArrayDeque<LocalPackage> pending = new ArrayDeque<>();
        for (PackageMajorVersion dependency : roots(rootConfig, selector.closure())) {
            pending.addLast(requirePackage(dependency, packages, workspaceRoot, distribution));
        }

        Set<PackageMajorVersion> visited = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            LocalPackage dependency = pending.removeFirst();
            if (!visited.add(dependency.version())) {
                continue;
            }
            selected.put(dependency.version(), dependency);
            Package config =
                    configs.computeIfAbsent(
                            dependency.version(), ignored -> readConfig(dependency.config()));
            for (PackageMajorVersion transitive :
                    transitiveDependencies(config, selector.closure())) {
                pending.addLast(requirePackage(transitive, packages, workspaceRoot, distribution));
            }
        }
        return selected;
    }

    private List<PackageMajorVersion> roots(Package config, String closure) {
        List<PackageMajorVersion> result = new ArrayList<>();
        if ("build".equals(closure) || "development".equals(closure)) {
            addDependencies(result, config.getBuildDependencies());
        }
        if ("development".equals(closure) || "runtime".equals(closure) || "test".equals(closure)) {
            addDependencies(result, config.getDependencies());
            addDependencies(result, config.getRuntimeDependencies());
        }
        if ("development".equals(closure) || "test".equals(closure)) {
            addDependencies(result, config.getTestDependencies());
        }
        return result;
    }

    private List<PackageMajorVersion> transitiveDependencies(Package config, String closure) {
        List<PackageMajorVersion> result = new ArrayList<>();
        // Build tools are prebuilt, so their runtime and public libraries are needed while running
        // them. Test libraries are part of development closures, including their transitive graph.
        addDependencies(result, config.getDependencies());
        addDependencies(result, config.getRuntimeDependencies());
        if ("development".equals(closure) || "test".equals(closure)) {
            addDependencies(result, config.getTestDependencies());
        }
        return result;
    }

    private void addDependencies(
            List<PackageMajorVersion> destination, Map<String, String> source) {
        if (source == null) {
            return;
        }
        source.forEach((name, version) -> destination.add(new PackageMajorVersion(name, version)));
    }

    private Map<PackageMajorVersion, LocalPackage> indexPackages(
            WorkspaceHelper workspace, Path workspaceRoot) {
        Map<PackageMajorVersion, LocalPackage> result = new LinkedHashMap<>();
        // Local checkouts win if the workspace contains both a checkout and a cached artifact.
        for (LocalPackage pkg : workspace.artifactPackages(workspaceRoot)) {
            result.put(pkg.version(), pkg);
        }
        for (LocalPackage pkg : workspace.sourcePackages(workspaceRoot)) {
            result.put(pkg.version(), pkg);
        }
        return result;
    }

    private LocalPackage findRoot(
            Map<PackageMajorVersion, LocalPackage> packages, PackageMajorVersion requested) {
        if (!requested.version().isEmpty()) {
            return requirePackage(requested, packages);
        }
        List<LocalPackage> matches =
                packages.values().stream()
                        .filter(pkg -> pkg.version().name().equals(requested.name()))
                        .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Package not found in workspace: " + requested.name());
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Multiple versions of package '"
                            + requested.name()
                            + "' are available; specify name@version in the selector.");
        }
        return matches.getFirst();
    }

    private LocalPackage requirePackage(
            PackageMajorVersion requested, Map<PackageMajorVersion, LocalPackage> packages) {
        LocalPackage pkg = packages.get(requested);
        if (pkg == null) {
            throw new IllegalArgumentException(
                    "Dependency " + requested + " is not available in the workspace or cache.");
        }
        return pkg;
    }

    private LocalPackage requirePackage(
            PackageMajorVersion requested,
            Map<PackageMajorVersion, LocalPackage> packages,
            Path workspaceRoot,
            Distribution distribution)
            throws Exception {

        LocalPackage existing = packages.get(requested);
        if (existing != null) {
            return existing;
        }

        Objects.requireNonNull(distribution);

        PackageSource source =
                distribution.getPackages().stream()
                        .filter(pkg -> pkg.getName().equals(requested.name()))
                        .filter(pkg -> pkg.getVersion().equals(requested.version()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No package with name '"
                                                        + requested.name()
                                                        + "' and version '"
                                                        + requested.version()
                                                        + "' found in distribution."));

        LocalPackage resolved = packageMapper.toLocalPackage(source, workspaceRoot);

        if (source instanceof Archive archive) {
            distributionArchiveHelper.extract(archive, workspaceRoot, resolved.source());
            packageSourceHelper.copy(resolved.source(), resolved.prefix());

            if (!Files.isRegularFile(resolved.config())) {
                throw new IOException(
                        "Package configuration not found in archive for " + requested);
            }
            packages.put(requested, resolved);
            return resolved;
        }
        if (source instanceof Folder nested) {
            Path metadataSource = packageSourceHelper.materialize(nested, workspaceRoot);
            if (!Files.isRegularFile(metadataSource.resolve(CONFIG_FILE))) {
                throw new IOException(
                        "Package configuration not found in source bundle for " + requested);
            }
            packages.put(requested, resolved);
            return resolved;
        }
        if (!(source instanceof GitRepository repository)) {
            throw new IllegalArgumentException(
                    "Dependency metadata for "
                            + requested
                            + " must be available from a Git repository.");
        }

        Path metadataSource =
                workspaceRoot
                        .resolve("private/cache/package-sources")
                        .resolve(requested.name())
                        .resolve(requested.version());
        Path config = metadataSource.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(config)) {
            deleteTree(metadataSource);
            GitRepositoryLocation location =
                    ImmutableGitRepositoryLocation.builder()
                            .repository(repository.getRepo())
                            .branch(repository.getBranch())
                            .build();
            git.clone(
                    ImmutableGitCloneInput.builder()
                            .parent(metadataSource.getParent())
                            .name(metadataSource.getFileName().toString())
                            .repository(location)
                            .build());
            if (!Files.isRegularFile(config)) {
                throw new IOException(
                        "Package configuration not found in repository for " + requested);
            }
        }

        packages.put(requested, resolved);
        return resolved;
    }

    private Package readConfig(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Package configuration not found: " + path);
        }
        return pklHelper.evaluate(path).as(Package.class);
    }

    private Selector parseSelector(String packageName, String closure) {
        if (closure == null || closure.isBlank()) {
            throw new IllegalArgumentException("A sysroot selector is required.");
        }
        String rootName = packageName;
        String selectedClosure = closure;
        int slash = closure.indexOf('/');
        if (slash >= 0) {
            String qualifiedRoot = closure.substring(0, slash);
            selectedClosure = closure.substring(slash + 1);
            int at = qualifiedRoot.lastIndexOf('@');
            if (at <= 0 || at == qualifiedRoot.length() - 1) {
                throw new IllegalArgumentException(
                        "Expected a selector in the form <packagename>@<version>/<selector>.");
            }
            rootName = qualifiedRoot;
        }
        if (!List.of("build", "development", "runtime", "test").contains(selectedClosure)) {
            throw new IllegalArgumentException(
                    "Unknown sysroot selector '"
                            + selectedClosure
                            + "'; expected build, development, or runtime.");
        }
        return new Selector(parsePackageName(rootName), selectedClosure);
    }

    private PackageMajorVersion parsePackageName(String value) {
        int at = value.lastIndexOf('@');
        if (at < 0) {
            return new PackageMajorVersion(value, "");
        }
        if (at == 0 || at == value.length() - 1) {
            throw new IllegalArgumentException("Expected package name in the form name@version.");
        }
        return new PackageMajorVersion(value.substring(0, at), value.substring(at + 1));
    }

    private void rebuildSysroot(Path sysroot, Iterable<LocalPackage> packages) throws IOException {
        List<LocalPackage> selected = new ArrayList<>();
        packages.forEach(selected::add);
        for (LocalPackage pkg : selected) {
            if (!Files.isDirectory(pkg.prefix())) {
                throw new IOException(
                        "Package artifacts are missing for "
                                + pkg.version()
                                + " at "
                                + pkg.prefix());
            }
        }
        deleteTree(sysroot);
        Files.createDirectories(sysroot);
        for (LocalPackage pkg : selected) {
            linkTree(pkg.prefix(), sysroot);
        }
    }

    private void linkTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) throws IOException {
                        Files.createDirectories(destination.resolve(source.relativize(directory)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        Path link = destination.resolve(source.relativize(file));
                        Files.createDirectories(link.getParent());
                        Files.deleteIfExists(link);
                        Files.createSymbolicLink(link, file.toAbsolutePath().normalize());
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException error)
                            throws IOException {
                        if (error != null) {
                            throw error;
                        }
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private record Selector(PackageMajorVersion root, String closure) {}
}
