package wstool.common.path;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.common.manifest.WorkspaceStorage;
import wstool.common.model.DependencyType;
import wstool.common.model.PackageDependency;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;

@Singleton
public class PathBuilder {
    private static final Logger LOGGER = LogManager.getLogger(PathBuilder.class);

    @Inject
    public PathBuilder() {}

    public String buildToolPath(LocalWorkspace workspace, PackageMajorVersion name)
            throws Exception {
        List<Path> closure = new ArrayList<>();

        Path packageSource = workspace.source(name);

        Path localBin = packageSource.resolve("wstool/bin");
        if (Files.isDirectory(localBin)) {
            closure.add(localBin);
        }

        Set<PackageMajorVersion> dependencies = new LinkedHashSet<>();
        addBuildDependencies(workspace, name, dependencies);
        for (PackageMajorVersion dep : dependencies) {
            Path buildClosure = workspace.buildClosureCache(dep);
            runtimeSymlinkFolder(workspace, dep, buildClosure);
            closure.add(buildClosure.resolve("bin"));
        }

        if (closure.isEmpty()) {
            throw new IllegalArgumentException(
                    "No build tools found for package "
                            + name.name()
                            + " in package "
                            + packageSource);
        }

        return closure.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    private void addBuildDependencies(
            LocalWorkspace workspace,
            PackageMajorVersion name,
            Set<PackageMajorVersion> dependencies)
            throws Exception {
        WorkspaceStorage storage = workspace.storage();

        for (PackageDependency dependency : storage.dependenciesOf(name)) {
            PackageMajorVersion buildDependency = dependency.dependency();
            if (dependency.type().equals(DependencyType.BUILD)) {
                dependencies.add(buildDependency);
            }
        }
    }

    public Set<PackageMajorVersion> runtimeClosure(
            LocalWorkspace workspace, PackageMajorVersion name) throws Exception {
        WorkspaceStorage storage = workspace.storage();
        Set<PackageMajorVersion> closure = new HashSet<>();
        closure.add(name);

        for (PackageDependency dependency : storage.dependenciesOf(name)) {
            if (dependency.type().equals(DependencyType.RUNTIME)
                    || dependency.type().equals(DependencyType.PUBLIC)) {
                closure.addAll(runtimeClosure(workspace, dependency.dependency()));
            }
        }

        return closure;
    }

    public void runtimeSymlinkFolder(LocalWorkspace workspace, PackageMajorVersion name, Path path)
            throws Exception {
        Set<PackageMajorVersion> closure = runtimeClosure(workspace, name);

        LOGGER.info(
                "Creating symlink folder for package {} with dependencies {} in workspace {} at {}",
                name,
                closure,
                workspace.path(),
                path);

        createSymlinkFolder(workspace, closure, path);
    }

    public void sysrootSymlinkFolder(
            LocalWorkspace workspace, PackageMajorVersion root, String selector, Path path)
            throws Exception {
        Set<PackageMajorVersion> closure = sysrootClosure(workspace, root, selector);

        LOGGER.info(
                "Creating {} sysroot for package {} with dependencies {} in workspace {} at {}",
                selector,
                root,
                closure,
                workspace.path(),
                path);

        createSymlinkFolder(workspace, closure, path);
    }

    private Set<PackageMajorVersion> sysrootClosure(
            LocalWorkspace workspace, PackageMajorVersion root, String selector) throws Exception {
        Map<String, Set<DependencyType>> modes =
                Map.of(
                        "build", EnumSet.of(DependencyType.BUILD),
                        "development",
                                EnumSet.of(
                                        DependencyType.BUILD,
                                        DependencyType.PUBLIC,
                                        DependencyType.TEST,
                                        DependencyType.RUNTIME),
                        "runtime", EnumSet.of(DependencyType.PUBLIC, DependencyType.RUNTIME));
        Set<DependencyType> dependencyTypes = modes.get(selector);
        if (dependencyTypes == null) {
            throw new IllegalArgumentException(
                    "Invalid sysroot selector '"
                            + selector
                            + "'. Expected build, development, or runtime.");
        }

        Set<PackageMajorVersion> closure = new LinkedHashSet<>();
        Queue<PackageMajorVersion> pending = new ArrayDeque<>();
        for (PackageDependency dependency : workspace.storage().dependenciesOf(root)) {
            if (dependencyTypes.contains(dependency.type())) {
                pending.add(dependency.dependency());
            }
        }

        while (!pending.isEmpty()) {
            PackageMajorVersion current = pending.remove();
            if (!closure.add(current)) {
                continue;
            }

            for (PackageDependency dependency : workspace.storage().dependenciesOf(current)) {
                if (dependencyTypes.contains(dependency.type())) {
                    pending.add(dependency.dependency());
                } else if (selector.equals("build")
                        && (dependency.type() == DependencyType.PUBLIC
                                || dependency.type() == DependencyType.RUNTIME)) {
                    // Build tools need their own runtime libraries and public dependencies.
                    pending.add(dependency.dependency());
                }
            }
        }

        return closure;
    }

    private void createSymlinkFolder(
            LocalWorkspace workspace, Set<PackageMajorVersion> closure, Path dst) throws Exception {

        Files.createDirectories(dst);
        for (PackageMajorVersion name : closure) {
            Path source = workspace.prefix(name);
            symlinkFolderContents(source, dst);
        }
    }

    private void symlinkFolderContents(Path source, Path target) throws Exception {
        LOGGER.info("Symlinking contents of {} to {}", source, target);

        Files.walkFileTree(
                source,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        Path relative = source.relativize(dir);
                        Path link = target.resolve(relative);
                        Files.createDirectories(link);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Path relative = source.relativize(file);
                        Path link = target.resolve(relative);

                        try {
                            Files.createSymbolicLink(link, file);
                        } catch (FileAlreadyExistsException e) {
                            // Ignore if the symlink already exists
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
