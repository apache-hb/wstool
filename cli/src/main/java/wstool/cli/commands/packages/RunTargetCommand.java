package wstool.cli.commands.packages;

import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "run")
public class RunTargetCommand implements Callable<Integer> {
    @Parameters
    List<String> args;

    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
