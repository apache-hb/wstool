package wstool.cli.commands.workspace;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
        name = "workspace",
        description = "Create and manage workspaces.",
        subcommands = {CreateWorkspaceCommand.class, AddPackageCommand.class})
public class WorkspaceCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        return 0;
    }
}
