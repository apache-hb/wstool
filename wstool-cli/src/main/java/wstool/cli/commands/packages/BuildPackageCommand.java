package wstool.cli.commands.packages;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Unmatched;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.packages.PackageFactory;
import wstool.common.path.ContainerBuildRunner;
import wstool.common.path.DockerBuildLimits;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "build",
        description = "Build a package in its configured Docker build image.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class BuildPackageCommand extends CommonCommand {
    @Option(
            names = "--memory",
            description = "Container memory and swap limit (for example 1536M).",
            converter = MemorySizeConverter.class)
    Long memoryBytes;

    @Option(names = "--disk-size", description = "Container writable layer limit (for example 2G).")
    String diskSize;

    @Option(names = "--tmpfs-size", description = "Container /tmp size limit (for example 512m).")
    String tmpfsSize;

    @Unmatched List<String> args = new ArrayList<>();

    private final WorkspaceFactory workspaceFactory;
    private final PackageFactory packageFactory;
    private final ContainerBuildRunner buildRunner;

    @Inject
    public BuildPackageCommand(
            WorkspaceFactory workspaceFactory,
            PackageFactory packageFactory,
            ContainerBuildRunner buildRunner) {
        this.workspaceFactory = workspaceFactory;
        this.packageFactory = packageFactory;
        this.buildRunner = buildRunner;
    }

    @Override
    public Integer call() throws Exception {
        var workspace = workspaceFactory.findUp(cwd);
        workspace.refresh();
        var pkg = packageFactory.findUp(cwd);
        DockerBuildLimits defaults = DockerBuildLimits.defaults();
        DockerBuildLimits limits =
                new DockerBuildLimits(
                        memoryBytes == null ? defaults.memoryBytes() : memoryBytes,
                        diskSize == null ? defaults.diskSize() : diskSize,
                        tmpfsSize == null ? defaults.tmpfsSize() : tmpfsSize);
        return buildRunner.runBuild(workspace, pkg, args, limits);
    }
}
