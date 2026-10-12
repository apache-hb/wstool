package wstool.cli.commands.packages;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;

@Command(
        name = "run",
        description = "Run a package build target.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RunTargetCommand extends CommonCommand {
    @Unmatched List<String> args = new ArrayList<>();

    @Inject
    public RunTargetCommand() {}

    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
