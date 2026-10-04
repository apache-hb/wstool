package wstool.cli.commands.workspace;

import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "add", description = "Add a package to the workspace.")
public class AddPackageCommand implements Callable<Integer> {
    @Option(
            names = {"--package", "-p"},
            required = true,
            arity = "1..*")
    List<String> packages;

    @Override
    public Integer call() {
        try {

            return 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
