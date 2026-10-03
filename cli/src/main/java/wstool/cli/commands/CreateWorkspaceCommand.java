package wstool.cli.commands;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "create", description = "Create a workspace.")
public class CreateWorkspaceCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        return 0;
    }
}
