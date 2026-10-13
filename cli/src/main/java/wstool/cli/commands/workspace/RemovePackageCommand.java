package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "remove",
        description = "Remove a cloned package from the workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RemovePackageCommand extends CommonCommand {
    @Option(names = {"--package", "-p"}, description = "The package to remove from the workspace.", required = true)
    String packageName;

    @Option(names = {"--version", "-v"}, description = "The version of the package to remove.")
    String version;

    private final WorkspaceFactory workspaceFactory;

    @Inject
    public RemovePackageCommand(WorkspaceFactory workspaceFactory) {
        this.workspaceFactory = workspaceFactory;
    }

    @Override
    public Integer call() throws Exception {
        var workspace = workspaceFactory.findUp(cwd);
        PackageMajorVersion name =
                version == null ? workspace.find(packageName) : new PackageMajorVersion(packageName, version);
        workspace.remove(name);
        return 0;
    }
}
