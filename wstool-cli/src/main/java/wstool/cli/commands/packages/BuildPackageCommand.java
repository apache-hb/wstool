package wstool.cli.commands.packages;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.packages.PackageFactory;
import wstool.common.path.ContainerBuildRunner;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "build",
        description = "Build a package in its configured Docker build image.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class BuildPackageCommand extends CommonCommand {
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
        return buildRunner.runBuild(workspace, pkg, args);
    }
}
