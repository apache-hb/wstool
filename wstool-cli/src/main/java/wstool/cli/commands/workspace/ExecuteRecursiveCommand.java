package wstool.cli.commands.workspace;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageConfig;
import wstool.common.packages.PackageFactory;
import wstool.common.workspace.LocalWorkspace;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "execute-recursive",
        description =
                "Execute a command in each local package source, starting with dependencies.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class ExecuteRecursiveCommand extends CommonCommand {
    @Parameters(index = "0..*", arity = "1..*", paramLabel = "<command>")
    List<String> command;

    private final WorkspaceFactory workspaceFactory;
    private final PackageFactory packageFactory;

    @Inject
    public ExecuteRecursiveCommand(
            WorkspaceFactory workspaceFactory, PackageFactory packageFactory) {
        this.workspaceFactory = workspaceFactory;
        this.packageFactory = packageFactory;
    }

    @Override
    public Integer call() throws Exception {
        LocalWorkspace workspace = workspaceFactory.findUp(cwd);
        PackageConfig root = packageFactory.findUp(cwd);

        Map<PackageMajorVersion, PackageConfig> packages = new HashMap<>();
        List<PackageConfig> executionOrder = new ArrayList<>();
        Set<PackageMajorVersion> visiting = new HashSet<>();
        Set<PackageMajorVersion> visited = new HashSet<>();

        collectExecutionOrder(workspace, root, packages, executionOrder, visiting, visited);

        for (PackageConfig pkg : executionOrder) {
            var processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workspace.source(pkg.name()).toFile());
            processBuilder.inheritIO();
            int result = processBuilder.start().waitFor();
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private void collectExecutionOrder(
            LocalWorkspace workspace,
            PackageConfig pkg,
            Map<PackageMajorVersion, PackageConfig> packages,
            List<PackageConfig> executionOrder,
            Set<PackageMajorVersion> visiting,
            Set<PackageMajorVersion> visited)
            throws Exception {
        PackageMajorVersion name = pkg.name();
        if (visited.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            throw new IllegalArgumentException("Dependency cycle detected while traversing " + name);
        }

        packages.put(name, pkg);
        List<PackageMajorVersion> dependencies =
                pkg.dependencies().values().stream()
                        .flatMap(List::stream)
                        .distinct()
                        .sorted(
                                Comparator.comparing(PackageMajorVersion::name)
                                        .thenComparing(PackageMajorVersion::version))
                        .toList();

        for (PackageMajorVersion dependency : dependencies) {
            PackageConfig dependencyConfig = packages.get(dependency);
            if (dependencyConfig == null) {
                var dependencySource = workspace.source(dependency);
                if (!Files.isRegularFile(dependencySource.resolve("wstool.pkl"))) {
                    continue;
                }
                dependencyConfig = packageFactory.open(dependencySource);
                packages.put(dependency, dependencyConfig);
            }
            collectExecutionOrder(
                    workspace,
                    dependencyConfig,
                    packages,
                    executionOrder,
                    visiting,
                    visited);
        }

        visiting.remove(name);
        visited.add(name);
        executionOrder.add(pkg);
    }
}
