package wstool.cli.commands.workspace;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.simple.DistributionHelper;
import wstool.simple.GitHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.WorkspaceHelper;
import wstool.simple.workspace.AddPackageToWorkspaceInput;
import wstool.simple.workspace.ImmutableAddPackageToWorkspaceInput;

@Command(name = "add", description = "Add a package to the workspace.")
public class AddPackageCommand implements Callable<Integer> {
    @Option(
            names = {"--package", "-p"},
            required = true)
    String packageName;

    @Option(
            names = {"--version"},
            required = false)
    String packageVersion;

    @Option(
            names = {"--interactive"},
            defaultValue = "true")
    boolean interactive;

    @Override
    public Integer call() {
        try {
            GitHelper git = new GitHelper(interactive);
            PklHelper pkl = new PklHelper(null);
            StorageHelper storage = new StorageHelper();
            DistributionHelper distribution = new DistributionHelper(pkl);
            WorkspaceHelper workspace = new WorkspaceHelper(git, storage, distribution);

            Path workspaceFolder = workspace.findWorkspaceUp(Path.of(""));

            AddPackageToWorkspaceInput input =
                    ImmutableAddPackageToWorkspaceInput.builder()
                            .workspace(workspaceFolder)
                            .name(packageName)
                            .version(packageVersion)
                            .build();

            workspace.addPackageToWorkspace(input);

            return 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
