package wstool.simple;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import wstool.Package;
import wstool.simple.model.PackageMajorVersion;

/** Resolves package dependency selectors and assembles their installation prefixes as a sysroot. */
public class DependencyResolver {
    private static final String CONFIG_FILE = "wstool.pkl";
    private static final String SYSROOT_DIRECTORY = ".wstool/sysroots";

    public Path createClosure(
            WorkspaceHelper workspace, Path workspaceRoot, String packageName, String closure) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Selector selector = parseSelector(packageName, closure);
        Map<PackageMajorVersion, LocalPackage> packages = indexPackages(workspace, workspaceRoot);
        LocalPackage root = findRoot(packages, selector.root());
        Path rootPath = root.source().resolve(CONFIG_FILE);

        try (PklHelper pkl = new PklHelper(null)) {
            Map<PackageMajorVersion, Package> configs = new LinkedHashMap<>();
            Package rootConfig = readConfig(pkl, rootPath);
            configs.put(root.version(), rootConfig);

            LinkedHashMap<PackageMajorVersion, LocalPackage> selected =
                    resolveDependencies(
                            selector,
                            rootConfig,
                            packages,
                            configs,
                            pkl);

            Path sysroot =
                    workspaceRoot
                            .resolve(SYSROOT_DIRECTORY)
                            .resolve(root.version().name() + "@" + root.version().version())
                            .resolve(selector.closure());
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

    private LinkedHashMap<PackageMajorVersion, LocalPackage> resolveDependencies(
            Selector selector,
            Package rootConfig,
            Map<PackageMajorVersion, LocalPackage> packages,
            Map<PackageMajorVersion, Package> configs,
            PklHelper pkl) {
        LinkedHashMap<PackageMajorVersion, LocalPackage> selected = new LinkedHashMap<>();
        ArrayDeque<LocalPackage> pending = new ArrayDeque<>();
        for (PackageMajorVersion dependency : roots(rootConfig, selector.closure())) {
            pending.addLast(requirePackage(dependency, packages));
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
                            dependency.version(),
                            ignored -> readConfig(pkl, dependency.source().resolve(CONFIG_FILE)));
            for (PackageMajorVersion transitive :
                    transitiveDependencies(config, selector.closure())) {
                pending.addLast(requirePackage(transitive, packages));
            }
        }
        return selected;
    }

    private List<PackageMajorVersion> roots(Package config, String closure) {
        List<PackageMajorVersion> result = new ArrayList<>();
        if ("build".equals(closure) || "development".equals(closure)) {
            addDependencies(result, config.getBuildDependencies());
        }
        if ("development".equals(closure) || "runtime".equals(closure)) {
            addDependencies(result, config.getDependencies());
            addDependencies(result, config.getRuntimeDependencies());
        }
        if ("development".equals(closure)) {
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
        if ("development".equals(closure)) {
            addDependencies(result, config.getTestDependencies());
        }
        return result;
    }

    private void addDependencies(List<PackageMajorVersion> destination, Map<String, String> source) {
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

    private Package readConfig(PklHelper pkl, Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Package configuration not found: " + path);
        }
        return pkl.evaluate(path).as(Package.class);
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
        if (!List.of("build", "development", "runtime").contains(selectedClosure)) {
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
