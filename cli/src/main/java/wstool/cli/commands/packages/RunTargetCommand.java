package wstool.cli.commands.packages;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

@Command(name = "run")
public class RunTargetCommand implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
