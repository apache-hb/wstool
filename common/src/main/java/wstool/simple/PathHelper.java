package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.DependencyType;

public class PathHelper {
    public PathHelper() {

    }

    public String buildClosurePath(Workspace workspace, PackageMajorVersion target) throws Exception {
        var storage = workspace.storage();
        List<Path> paths = new ArrayList<>();

        findLocalTools(storage, target).ifPresent(paths::add);

        for (var dependency : storage.directDependenciesOf(target, DependencyType.BUILD)) {
            var pkg = storage.find(dependency);
            paths.add(pkg.prefix().resolve("bin"));
        }

        if (paths.isEmpty()) {
            throw new IllegalStateException("Package " + target + " must have at least one build dependency or local tools directory");
        }

        return paths.stream()
            .map(Path::toAbsolutePath)
            .map(Path::toString)
            .collect(Collectors.joining(":"));
    }

    private Optional<Path> findLocalTools(WorkspaceStorage storage, PackageMajorVersion name) throws Exception {
        var pkg = storage.find(name);
        Path localTools = pkg.source().resolve("wstool/bin");
        if (Files.isDirectory(localTools)) {
            return Optional.of(localTools);
        }

        return Optional.empty();
    }
}
