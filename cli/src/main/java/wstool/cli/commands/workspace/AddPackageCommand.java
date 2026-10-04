package wstool.cli.commands.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.workspace.GitHelper;
import wstool.workspace.LocalWorkspace;
import wstool.workspace.PklHelper;

@Command(name = "add", description = "Add a package to the workspace.")
public class AddPackageCommand implements Callable<Integer> {
    @Option(names = { "--package", "-p" }, required = true, arity = "1..*")
    List<String> packages;

    @Override
    public Integer call() {
        try {
            GitHelper git = new GitHelper(null, null);
            PklHelper pkl = new PklHelper(Map.of());
            LocalWorkspace workspace = LocalWorkspace.findUp(git, pkl, Path.of(""));

            for (String pkg : packages) {
                workspace.clone(pkg);
            }

            return 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
