package wstool.cli.commands.packages;

import jakarta.inject.Inject;
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
import wstool.simple.Workspace;
import wstool.simple.WorkspaceFactory;
import wstool.simple.tool.ImmutableRunBuildToolInput;

@Command(
        name = "run",
        description = "Run a package build target.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RunTargetCommand extends CommonCommand {
    @Unmatched List<String> args = new ArrayList<>();

    private final BuildToolHelper buildToolHelper;
    private final PackageFactory packageFactory;
    private final WorkspaceFactory workspaceFactory;

    @Inject
    public RunTargetCommand(
            BuildToolHelper buildToolHelper,
            PackageFactory packageFactory,
            WorkspaceFactory workspaceFactory) {
        this.buildToolHelper = buildToolHelper;
        this.packageFactory = packageFactory;
        this.workspaceFactory = workspaceFactory;
    }

    @Override
    public Integer call() throws Exception {
        try (Workspace workspace = workspaceFactory.findUp(cwd)) {
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
