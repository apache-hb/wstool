package wstool.cli.commands;

import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;

@Command(
        name = "sysroot",
        description = "Create a sysroot from workspace package artifacts.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class SysrootCommand extends CommonCommand {
    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
