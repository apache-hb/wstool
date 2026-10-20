package wstool.cli.commands;

import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;

@Command(
        name = "build",
        description = "Build a workspace package in a container.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class BuildCommand extends CommonCommand {
    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
