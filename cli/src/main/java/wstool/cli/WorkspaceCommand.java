package wstool.cli;

import picocli.CommandLine.Command;

@Command(
        name = "workspace",
        description = "Create and manage workspaces.",
        mixinStandardHelpOptions = true,
        subcommands = {
            WorkspaceCreateCommand.class,
            WorkspaceSetupCommand.class,
            WorkspaceCloneCommand.class
        })
public final class WorkspaceCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("Use workspace create, setup, or clone.");
    }
}
