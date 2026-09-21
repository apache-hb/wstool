package wstool.common;

import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.common.model.PackageDescriptor;
import wstool.common.model.PackageVersion;

public final class BuildService {
    private static final Logger LOG = LogManager.getLogger(BuildService.class);
    private final ArtifactStore store;

    @Inject
    public BuildService(ArtifactStore store) {
        this.store = store;
    }

    public void buildWorkspace(WorkspaceLayout layout, boolean docker, String image) {
        var packages = discover(layout);
        var order = order(packages);
        for (var descriptor : order) {
            prepareDependencies(layout, descriptor, packages);
            build(layout, descriptor, docker, image);
            store.publish(descriptor.version(), layout.stageDirectory(descriptor));
        }
    }

    public void release(
            WorkspaceLayout layout, PackageDescriptor descriptor, boolean docker, String image) {
        prepareDependencies(layout, descriptor, discover(layout));
        build(layout, descriptor, docker, image);
        store.publish(descriptor.version(), layout.stageDirectory(descriptor));
    }

    public void fetchDependency(WorkspaceLayout layout, PackageVersion version) {
        var artifacts = store.fetch(version, layout.cache());
        copyTree(artifacts, layout.sysroot());
    }

    public void materializeDependencies(WorkspaceLayout layout, PackageDescriptor descriptor) {
        materializeDependencies(layout, descriptor, "development");
    }

    public void materializeDependencies(
            WorkspaceLayout layout, PackageDescriptor descriptor, String selector) {
        var local = discover(layout);
        var dependencies =
                switch (selector) {
                    case "build" -> descriptor.buildDependencies();
                    case "runtime" ->
                            merge(descriptor.dependencies(), descriptor.runtimeDependencies());
                    case "development" -> descriptor.allDependencies();
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown sysroot selector: " + selector);
                };
        for (var dependency : dependencies.entrySet()) {
            if (local.containsKey(dependency.getKey())) {
                var localDescriptor = local.get(dependency.getKey());
                var stage = layout.stageDirectory(localDescriptor);
                if (Files.exists(stage)) copyTree(stage, layout.sysroot());
            } else {
                fetchDependency(
                        layout, new PackageVersion(dependency.getKey(), dependency.getValue()));
            }
        }
    }

    private static Map<String, String> merge(
            Map<String, String> first, Map<String, String> second) {
        var result = new LinkedHashMap<String, String>(first);
        result.putAll(second);
        return result;
    }

    private void prepareDependencies(
            WorkspaceLayout layout,
            PackageDescriptor descriptor,
            Map<String, PackageDescriptor> localPackages) {
        for (var dependency : descriptor.allDependencies().entrySet()) {
            var local = localPackages.get(dependency.getKey());
            if (local != null) {
                continue;
            }
            fetchDependency(layout, new PackageVersion(dependency.getKey(), dependency.getValue()));
        }
    }

    private void build(
            WorkspaceLayout layout, PackageDescriptor descriptor, boolean docker, String image) {
        var source = descriptor.source();
        var buildDir = layout.buildDirectory(descriptor);
        var stageDir = layout.stageDirectory(descriptor);
        delete(buildDir);
        delete(stageDir);
        createDirectories(buildDir, stageDir, layout.sysroot());

        if (!Files.exists(source.resolve("CMakeLists.txt"))) {
            writeManifest(stageDir, descriptor);
            LOG.info(
                    "{} has no CMake project; treating it as a system package",
                    descriptor.version());
            return;
        }
        if (!descriptor.buildTool().equals("cmake")
                && !descriptor.buildTool().equals("wstool-cmake")) {
            throw new IllegalArgumentException(
                    "Unsupported buildTool '"
                            + descriptor.buildTool()
                            + "' for "
                            + descriptor.version());
        }

        var sourcePath = pathForBuild(source, layout, docker);
        var buildPath = pathForBuild(buildDir, layout, docker);
        var stagePath = pathForBuild(stageDir, layout, docker);
        var sysrootPath = pathForBuild(layout.sysroot(), layout, docker);
        run(
                docker,
                image,
                layout,
                "cmake",
                "-S",
                sourcePath,
                "-B",
                buildPath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_INSTALL_PREFIX=" + stagePath,
                "-DWSTOOL_SYSROOT=" + sysrootPath,
                "-DCMAKE_PREFIX_PATH=" + sysrootPath);
        run(docker, image, layout, "cmake", "--build", buildPath, "--parallel");
        run(docker, image, layout, "cmake", "--install", buildPath);
        if (Files.exists(buildDir.resolve("CTestTestfile.cmake"))) {
            run(docker, image, layout, "ctest", "--test-dir", buildPath, "--output-on-failure");
        }
        copyTree(stageDir, layout.sysroot());
    }

    private static String pathForBuild(Path path, WorkspaceLayout layout, boolean docker) {
        if (docker) {
            return "/workspace/"
                    + layout.root()
                            .relativize(path)
                            .toString()
                            .replace(path.getFileSystem().getSeparator(), "/");
        }
        return path.toString();
    }

    private static void run(
            boolean docker, String image, WorkspaceLayout layout, String... command) {
        var args = new ArrayList<String>();
        if (docker) {
            args.addAll(
                    List.of(
                            "docker",
                            "run",
                            "--rm",
                            "-v",
                            layout.root() + ":/workspace",
                            "-w",
                            "/workspace",
                            image));
        }
        args.addAll(List.of(command));
        LOG.info("Running {}", String.join(" ", args));
        try {
            var process = new ProcessBuilder(args).inheritIO().start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException(
                        "Build command failed with exit code "
                                + process.exitValue()
                                + ": "
                                + String.join(" ", args));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to start build command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Build interrupted", e);
        }
    }

    private static Map<String, PackageDescriptor> discover(WorkspaceLayout layout) {
        var result = new LinkedHashMap<String, PackageDescriptor>();
        try (var paths = Files.list(layout.sources())) {
            paths.filter(Files::isDirectory)
                    .forEach(
                            path -> {
                                var descriptorFile = path.resolve("wstool.pkl");
                                if (!Files.exists(descriptorFile))
                                    descriptorFile = path.resolve("Package.pkl");
                                if (Files.exists(descriptorFile)) {
                                    var descriptor = PackageDescriptorReader.read(path);
                                    result.put(descriptor.version().name(), descriptor);
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to discover workspace packages", e);
        }
        return result;
    }

    private static List<PackageDescriptor> order(Map<String, PackageDescriptor> packages) {
        var result = new ArrayList<PackageDescriptor>();
        var visiting = new HashSet<String>();
        var visited = new HashSet<String>();
        for (var packageName : packages.keySet())
            visit(packageName, packages, visiting, visited, result);
        return result;
    }

    private static void visit(
            String name,
            Map<String, PackageDescriptor> packages,
            Set<String> visiting,
            Set<String> visited,
            List<PackageDescriptor> result) {
        if (visited.contains(name)) return;
        if (!visiting.add(name))
            throw new IllegalStateException("Dependency cycle involving " + name);
        var descriptor = packages.get(name);
        if (descriptor == null) return;
        for (var dependency : descriptor.allDependencies().keySet()) {
            if (packages.containsKey(dependency))
                visit(dependency, packages, visiting, visited, result);
        }
        visiting.remove(name);
        visited.add(name);
        result.add(descriptor);
    }

    private static void writeManifest(Path stage, PackageDescriptor descriptor) {
        try {
            Files.writeString(
                    stage.resolve("wstool-package.txt"),
                    descriptor.version() + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyTree(Path source, Path destination) {
        try {
            if (!Files.exists(source)) return;
            Files.walk(source)
                    .forEach(
                            path -> {
                                var target =
                                        destination.resolve(source.relativize(path).toString());
                                try {
                                    if (Files.isDirectory(path)) Files.createDirectories(target);
                                    else {
                                        Files.createDirectories(target.getParent());
                                        Files.copy(
                                                path, target, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path path) {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            item -> {
                                try {
                                    Files.deleteIfExists(item);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void createDirectories(Path... paths) {
        for (var path : paths) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
