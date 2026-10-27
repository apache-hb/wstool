package wstool.cli.commands.packages;

import javax.inject.Inject;
import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.packages.PackageFactory;
import wstool.common.path.PackageArtifactStore;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "store",
        description = "Store the current package build artifacts in the distribution S3 bucket.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class StorePackageCommand extends CommonCommand {
    private final WorkspaceFactory workspaceFactory;
    private final PackageFactory packageFactory;
    private final PackageArtifactStore artifactStore;

    @Inject
    public StorePackageCommand(
            WorkspaceFactory workspaceFactory,
            PackageFactory packageFactory,
            PackageArtifactStore artifactStore) {
        this.workspaceFactory = workspaceFactory;
        this.packageFactory = packageFactory;
        this.artifactStore = artifactStore;
    }

    @Override
    public Integer call() throws Exception {
        var workspace = workspaceFactory.findUp(cwd);
        var pkg = packageFactory.findUp(cwd);
        artifactStore.store(workspace, pkg);
        return 0;
    }
}
