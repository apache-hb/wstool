package wstool.cli.commands.workspace;

import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;

@Command(
        name = "workspace",
        description = "Create and manage workspaces.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
            CreateWorkspaceCommand.class,
            SetupWorkspaceCommand.class,
            AddPackageCommand.class,
            RemovePackageCommand.class
        })
public class WorkspaceCommand extends CommonCommand {
    @Override
    public Integer call() {
        return 0;
    }
}
