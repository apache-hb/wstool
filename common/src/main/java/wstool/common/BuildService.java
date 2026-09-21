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
import wstool.common.model.BuildOptions;
import wstool.common.model.PackageDescriptor;
import wstool.common.model.PackageVersion;

public final class BuildService {
    private static final Logger LOG = LogManager.getLogger(BuildService.class);
    private final ArtifactStore store;

    @Inject
    public BuildService(ArtifactStore store) {
        this.store = store;
    }

    public void buildWorkspace(WorkspaceLayout layout, boolean docker) {
        var packages = discover(layout);
        var order = order(packages);
        for (var descriptor : order) {
            prepareDependencies(layout, descriptor, packages);
            build(layout, descriptor, docker);
            store.publish(descriptor.version(), layout.stageDirectory(descriptor));
        }
    }

    public void release(WorkspaceLayout layout, PackageDescriptor descriptor, boolean docker) {
        prepareDependencies(layout, descriptor, discover(layout));
        build(layout, descriptor, docker);
        store.publish(descriptor.version(), layout.stageDirectory(descriptor));
    }

    public void runTarget(
            WorkspaceLayout layout,
            PackageDescriptor descriptor,
            String target,
            List<String> arguments,
            boolean docker) {
        materializeDependencies(layout, descriptor, "build");
        var useDocker = docker || descriptor.buildOptions().buildImage().equals("bootstrap");
        var buildDirectory = layout.buildDirectory(descriptor);
        var sourcePath = pathForBuild(descriptor.source(), layout, useDocker);
        var buildPath = pathForBuild(buildDirectory, layout, useDocker);
        var stagePath = pathForBuild(layout.stageDirectory(descriptor), layout, useDocker);
        var sysrootPath = pathForBuild(layout.sysroot(), layout, useDocker);
        var tool = layout.sysroot().resolve("bin").resolve(descriptor.buildTool());
        if (!Files.exists(tool)) {
            throw new IllegalStateException(
                    "Build tool "
                            + descriptor.buildTool()
                            + " is not installed in "
                            + layout.sysroot());
        }
        var command =
                new ArrayList<String>(
                        List.of(
                                pathForBuild(tool, layout, useDocker),
                                sourcePath,
                                buildPath,
                                stagePath,
                                sysrootPath,
                                target));
        command.addAll(arguments);
        run(
                useDocker,
                layout,
                descriptor.buildOptions(),
                command,
                descriptor.buildOptions().effectiveDockerImage());
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

    private void build(WorkspaceLayout layout, PackageDescriptor descriptor, boolean docker) {
        var source = descriptor.source();
        var buildOptions = descriptor.buildOptions();
        var useDocker = docker || buildOptions.buildImage().equals("bootstrap");
        var buildDir = layout.buildDirectory(descriptor);
        var stageDir = layout.stageDirectory(descriptor);
        delete(buildDir);
        delete(stageDir);
        createDirectories(buildDir, stageDir, layout.sysroot());

        if (descriptor.buildTool().equals("system")) {
            copyTree(source.resolve("bin"), stageDir.resolve("bin"));
            writeManifest(stageDir, descriptor);
            copyTree(stageDir, layout.sysroot());
            LOG.info("{} is a system package", descriptor.version());
            return;
        }

        var sourcePath = pathForBuild(source, layout, useDocker);
        var buildPath = pathForBuild(buildDir, layout, useDocker);
        var stagePath = pathForBuild(stageDir, layout, useDocker);
        var sysrootPath = pathForBuild(layout.sysroot(), layout, useDocker);
        var tool = layout.sysroot().resolve("bin").resolve(descriptor.buildTool());
        if (!Files.exists(tool)) {
            throw new IllegalStateException(
                    "Build tool "
                            + descriptor.buildTool()
                            + " is not installed in "
                            + layout.sysroot());
        }
        run(
                useDocker,
                layout,
                buildOptions,
                buildOptions.effectiveDockerImage(),
                pathForBuild(tool, layout, useDocker),
                sourcePath,
                buildPath,
                stagePath,
                sysrootPath);
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
            boolean docker,
            WorkspaceLayout layout,
            BuildOptions buildOptions,
            String image,
            String... command) {
        run(docker, layout, buildOptions, List.of(command), image);
    }

    private static void run(
            boolean docker,
            WorkspaceLayout layout,
            BuildOptions buildOptions,
            List<String> command,
            String image) {
        var args = new ArrayList<String>();
        if (docker) {
            args.addAll(List.of("docker", "run", "--rm"));
            if (buildOptions.networkIsolation().equals("isolated"))
                args.addAll(List.of("--network", "none"));
            var mountMode = buildOptions.sourceMount().equals("readonly") ? "ro" : "rw";
            args.addAll(List.of("-v", layout.root() + ":/workspace:" + mountMode));
            if (mountMode.equals("ro"))
                args.addAll(List.of("-v", layout.metadata() + ":/workspace/.wstool:rw"));
            args.addAll(List.of("-w", "/workspace", image));
        }
        args.addAll(command);
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
                                                path,
                                                target,
                                                StandardCopyOption.REPLACE_EXISTING,
                                                StandardCopyOption.COPY_ATTRIBUTES);
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
