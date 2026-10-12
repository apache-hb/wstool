package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;

@Command(
        name = "add",
        aliases = "clone",
        description = "Add a package to the workspace.",
        versionProvider = VersionProvider.class)
public class AddPackageCommand extends CommonCommand {
    @Inject
    public AddPackageCommand() {}

    @Override
    public Integer call() {
        return 0;
    }
}
