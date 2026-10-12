package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;

@Command(
        name = "create",
        description = "Create a workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class CreateWorkspaceCommand extends CommonCommand {
    @Inject
    public CreateWorkspaceCommand() {}

    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
