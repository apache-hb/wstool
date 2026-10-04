package wstool.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wstool.Package;
import wstool.common.api.CodePackage;
import wstool.common.model.PackageMajorVersion;

public class SysrootManager {
    public enum SysrootTarget {
        BUILD,
        DEPENDENCIES,
        TEST_DEPENDENCIES,
        RUNTIME_DEPENDENCIES
    }

    /** Creates or updates a sysroot by merging the selected package prefixes into {@code root}. */
    public Path createSysroot(
            Path root, LocalWorkspace workspace, CodePackage pkg, SysrootTarget target)
            throws Exception {
        Files.createDirectories(root);
        for (CodePackage dependency : gatherTargetPackages(pkg, workspace, target)) {
            if (!Files.isDirectory(dependency.prefix())) {
                throw new IOException(
                        "Package artifacts are missing for "
                                + dependency.version()
                                + " at "
                                + dependency.prefix());
            }
            mergeTree(dependency.prefix(), root);
        }
        return root;
    }

    private List<CodePackage> gatherTargetPackages(
            CodePackage top, LocalWorkspace workspace, SysrootTarget target) throws Exception {
        Map<PackageMajorVersion, CodePackage> selected = new LinkedHashMap<>();
        Package topConfig = workspace.getPackage(top.version().name()).packageConfig();
        Map<String, String> roots = dependencies(topConfig, target);
        for (Map.Entry<String, String> entry : roots.entrySet()) {
            visitDependency(
                    workspace,
                    entry.getKey(),
                    entry.getValue(),
                    target == SysrootTarget.TEST_DEPENDENCIES,
                    selected);
        }
        return new ArrayList<>(selected.values());
    }

    private void visitDependency(
            LocalWorkspace workspace,
            String name,
            String requestedVersion,
            boolean includeTestDependencies,
            Map<PackageMajorVersion, CodePackage> selected)
            throws Exception {
        LocalPackage local = workspace.getPackage(name);
        Package config = local.packageConfig();
        if (!config.getPackage().getVersion().equals(requestedVersion)) {
            throw new IllegalArgumentException(
                    "Dependency "
                            + name
                            + " requires version "
                            + requestedVersion
                            + " but the workspace has "
                            + config.getPackage().getVersion());
        }
        PackageMajorVersion version =
                new PackageMajorVersion(
                        config.getPackage().getName(), config.getPackage().getVersion());
        if (selected.containsKey(version)) {
            return;
        }

        CodePackage codePackage = new WorkspaceCodePackage(version, local.source(), local.prefix());
        selected.put(version, codePackage);

        // Build tools need their ordinary and runtime libraries available too. Test dependencies
        // are included only for the explicit test selector.
        for (Map.Entry<String, String> dependency :
                dependencyClosure(config, includeTestDependencies).entrySet()) {
            visitDependency(
                    workspace,
                    dependency.getKey(),
                    dependency.getValue(),
                    includeTestDependencies,
                    selected);
        }
    }

    private Map<String, String> dependencies(Package config, SysrootTarget target) {
        Map<String, String> result = new LinkedHashMap<>();
        switch (target) {
            case BUILD -> add(result, config.getBuildDependencies());
            case DEPENDENCIES, RUNTIME_DEPENDENCIES -> {
                add(result, config.getDependencies());
                add(result, config.getRuntimeDependencies());
            }
            case TEST_DEPENDENCIES -> {
                add(result, config.getDependencies());
                add(result, config.getTestDependencies());
                add(result, config.getRuntimeDependencies());
            }
        }
        return result;
    }

    private Map<String, String> dependencyClosure(Package config, boolean includeTestDependencies) {
        Map<String, String> result = new LinkedHashMap<>();
        add(result, config.getDependencies());
        add(result, config.getRuntimeDependencies());
        if (includeTestDependencies) {
            add(result, config.getTestDependencies());
        }
        return result;
    }

    private void add(Map<String, String> destination, Map<String, String> source) {
        if (source != null) {
            destination.putAll(source);
        }
    }

    private void mergeTree(Path source, Path destination) throws IOException {
        try {
            Files.walkFileTree(
                    source,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path directory, BasicFileAttributes attributes) throws IOException {
                            Files.createDirectories(
                                    destination.resolve(source.relativize(directory)));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                                throws IOException {
                            Path target = destination.resolve(source.relativize(file));
                            Files.createDirectories(target.getParent());
                            if (Files.isSymbolicLink(file)) {
                                Files.deleteIfExists(target);
                                Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                            } else {
                                Files.copy(
                                        file,
                                        target,
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.COPY_ATTRIBUTES);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (UnsupportedOperationException e) {
            throw new IOException("Unable to merge package artifacts into sysroot", e);
        }
    }
}
