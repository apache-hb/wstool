package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "add",
        aliases = "clone",
        description = "Add a package to the workspace.",
        versionProvider = VersionProvider.class)
public class AddPackageCommand extends CommonCommand {
    @Option(names = {"--package", "-p"}, description = "The package to add to the workspace.", required = true)
    String packageName;

    @Option(names = {"--version", "-v"}, description = "The version of the package to add to the workspace.", required = false)
    String version;

    private final WorkspaceFactory workspaceFactory;

    @Inject
    public AddPackageCommand(WorkspaceFactory workspaceFactory) {
        this.workspaceFactory = workspaceFactory;
    }

    @Override
    public Integer call() throws Exception {
        LocalWorkspace workspace = workspaceFactory.findUp(cwd);
        workspace.refresh();

        if (version == null) {
            PackageMajorVersion name = workspace.find(packageName);
            workspace.clone(name);
        } else {
            workspace.clone(new PackageMajorVersion(packageName, version));
        }
        return 0;
    }
}
