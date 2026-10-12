package wstool.cli.commands.workspace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommandHelpers;
import wstool.cli.commands.CommonCommand;
import wstool.simple.GitHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.WorkspaceHelper;

@Command(
        name = "remove",
        description = "Remove a cloned package from the workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RemovePackageCommand extends CommonCommand {
    @Option(
            names = {"--package", "-p"},
            required = true,
            description = "Package name to remove.")
    private String packageName;

    @Override
    public Integer call() throws Exception {
        try (PklHelper pkl = new PklHelper(null)) {
            WorkspaceHelper workspace =
                    CommandHelpers.workspace(new GitHelper(false), new StorageHelper(), pkl);
            workspace.removePackageFromWorkspace(
                    workspace.findWorkspaceUp(cwd), packageName);
            return 0;
        }
    }
}
