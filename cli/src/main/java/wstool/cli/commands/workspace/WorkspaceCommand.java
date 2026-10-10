package wstool.cli.commands.workspace;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;

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
public class WorkspaceCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        return 0;
    }
}
