package wstool.cli.commands.packages;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.packages.PackageConfig;
import wstool.common.packages.PackageFactory;
import wstool.common.path.BuildRunner;
import wstool.common.workspace.LocalWorkspace;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "run",
        description = "Run a package build target.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RunTargetCommand extends CommonCommand {
    @Unmatched List<String> args = new ArrayList<>();

    private final WorkspaceFactory workspaceFactory;
    private final PackageFactory packageFactory;
    private final BuildRunner buildRunner;

    @Inject
    public RunTargetCommand(
            WorkspaceFactory workspaceFactory,
            PackageFactory packageFactory,
            BuildRunner buildRunner) {
        this.workspaceFactory = workspaceFactory;
        this.packageFactory = packageFactory;
        this.buildRunner = buildRunner;
    }

    @Override
    public Integer call() throws Exception {
        LocalWorkspace workspace = workspaceFactory.findUp(cwd);
        workspace.refresh();
        var packageDirectory = packageFactory.findDirectoryUp(cwd);
        PackageConfig pkgConfig = packageFactory.open(packageDirectory);
        return buildRunner.runBuild(workspace, pkgConfig, packageDirectory, args);
    }
}
