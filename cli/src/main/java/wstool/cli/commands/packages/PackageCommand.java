package wstool.cli.commands.packages;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;

@Command(
        name = "package",
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
