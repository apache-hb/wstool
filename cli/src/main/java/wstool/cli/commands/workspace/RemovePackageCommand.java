package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
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

    private final WorkspaceHelper workspace;

    @Inject
    public RemovePackageCommand(WorkspaceHelper workspace) {
        this.workspace = workspace;
    }

    @Override
    public Integer call() throws Exception {
        workspace.removePackageFromWorkspace(workspace.findWorkspaceUp(cwd), packageName);
        return 0;
    }
}
