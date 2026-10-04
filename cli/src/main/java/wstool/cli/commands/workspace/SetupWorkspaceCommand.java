package wstool.cli.commands.workspace;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "setup")
public class SetupWorkspaceCommand implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
