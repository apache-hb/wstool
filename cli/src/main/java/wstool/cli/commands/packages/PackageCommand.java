package wstool.cli.commands.packages;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.cli.VersionProvider;

@Command(
        name = "package",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {RunTargetCommand.class})
public class PackageCommand implements Callable<Integer> {
    @Unmatched private List<String> targetArguments = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        if (targetArguments.isEmpty()) {
            return 0;
        }
        return new RunTargetCommand(targetArguments).call();
    }
}
