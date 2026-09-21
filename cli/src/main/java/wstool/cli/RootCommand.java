package wstool.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "wstool",
        description = "Workspace tooling.",
        mixinStandardHelpOptions = true,
        version = "wstool " + RootCommand.VERSION
)
public final class RootCommand implements Callable<Integer> {
    static final String VERSION = "0.1.0";

    @Override
    public Integer call() {
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RootCommand()).execute(args);
        System.exit(exitCode);
    }
}
