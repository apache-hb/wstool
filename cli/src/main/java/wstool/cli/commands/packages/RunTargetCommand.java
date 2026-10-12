package wstool.cli.commands.packages;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.simple.BuildToolHelper;
import wstool.simple.LocalPackage;
import wstool.simple.MaterializedPackage;
import wstool.simple.PackageFactory;
import wstool.simple.PathHelper;
import wstool.simple.PklHelper;
import wstool.simple.Workspace;
import wstool.simple.WorkspaceFactory;
import wstool.simple.distribution.DistributionFactory;
import wstool.simple.tool.ImmutableRunBuildToolInput;

@Command(
        name = "run",
        description = "Run a package build target.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RunTargetCommand extends CommonCommand {
    @Unmatched List<String> args = new ArrayList<>();

    public RunTargetCommand() {}

    RunTargetCommand(List<String> args) {
        this.args = new ArrayList<>(args);
    }

    @Override
    public Integer call() throws Exception {
        try (PklHelper pkl = new PklHelper(null)) {
            PathHelper pathHelper = new PathHelper();
            DistributionFactory distributionFactory = new DistributionFactory(pkl);
            PackageFactory packageFactory = new PackageFactory(pkl);
            WorkspaceFactory workspaceFactory = new WorkspaceFactory(pkl, distributionFactory, packageFactory);
            BuildToolHelper buildToolHelper = new BuildToolHelper(pathHelper);

            try (Workspace workspace = workspaceFactory.findUp(cwd)) {
                workspace.refresh();

                MaterializedPackage current = packageFactory.findUp(workspace, cwd);

                List<String> command = new ArrayList<>();
                if (current instanceof LocalPackage local) {
                    command.add(local.buildTool());
                }
                command.addAll(args);
                return buildToolHelper.run(
                        ImmutableRunBuildToolInput.builder()
                                .workspace(workspace)
                                .name(current.name())
                                .arguments(command)
                                .build());
            }
        }
    }
}
