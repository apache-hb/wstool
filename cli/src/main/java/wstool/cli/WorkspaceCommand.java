package wstool.cli;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
    private static final Logger LOG = LogManager.getLogger(WorkspaceCommand.class);

    @Override
    public void run() {
        LOG.info("Use workspace create, setup, or clone.");
    }
}
