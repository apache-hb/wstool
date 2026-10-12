package wstool.cli.commands.workspace;

import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;

@Command(
        name = "remove",
        description = "Remove a cloned package from the workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RemovePackageCommand extends CommonCommand {
    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
