package wstool.cli.commands;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "add", description = "Add a package to the workspace.")
public class AddPackageCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        return 0;
    }
}
