package wstool.cli.commands.workspace;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.simple.DistributionHelper;
import wstool.simple.GitHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.WorkspaceHelper;

@Command(
        name = "remove",
        description = "Remove a cloned package from the workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RemovePackageCommand implements Callable<Integer> {
    @Option(
            names = {"--package", "-p"},
            required = true,
            description = "Package name to remove.")
    private String packageName;

    @Override
    public Integer call() throws Exception {
        try (PklHelper pkl = new PklHelper(null)) {
            WorkspaceHelper workspace =
                    new WorkspaceHelper(
                            new GitHelper(false), new StorageHelper(), new DistributionHelper(pkl));
            workspace.removePackageFromWorkspace(
                    workspace.findWorkspaceUp(Path.of("")), packageName);
            return 0;
        }
    }
}
