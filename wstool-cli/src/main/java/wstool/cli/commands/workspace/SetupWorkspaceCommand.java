package wstool.cli.commands.workspace;

import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;

@Command(
        name = "setup",
        description = "Set the distribution for this workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class SetupWorkspaceCommand extends CommonCommand {
    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
