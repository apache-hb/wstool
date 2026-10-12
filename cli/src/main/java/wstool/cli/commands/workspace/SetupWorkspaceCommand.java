package wstool.cli.commands.workspace;

import java.net.URI;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommandHelpers;
import wstool.cli.commands.CommonCommand;
import wstool.simple.GitHelper;
import wstool.simple.PackageFactory;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.Workspace;
import wstool.simple.WorkspaceFactory;
import wstool.simple.WorkspaceHelper;
import wstool.simple.distribution.DistributionFactory;
import wstool.simple.git.ImmutableGitRepositoryLocation;

@Command(
        name = "setup",
        description = "Set the distribution for this workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class SetupWorkspaceCommand extends CommonCommand {
    @Option(names = "--from", required = true, description = "Distribution Git URL.")
    private URI repository;

    @Option(names = "--branch", description = "Git branch to clone.")
    private String branch;

    @Override
    public Integer call() throws Exception {
        try (PklHelper pkl = new PklHelper(null)) {
            WorkspaceHelper workspaceHelper = CommandHelpers.workspace(new GitHelper(false), new StorageHelper(), pkl);
            PackageFactory packageFactory = new PackageFactory(pkl);
            DistributionFactory distributionFactory = new DistributionFactory(pkl);
            WorkspaceFactory workspaceFactory = new WorkspaceFactory(pkl, distributionFactory, packageFactory);

            try (Workspace workspace = workspaceFactory.findUp(cwd)) {
                var distribution = ImmutableGitRepositoryLocation.builder()
                    .repository(repository)
                    .branch(branch)
                    .build();

                workspaceHelper.setup(workspace, distribution);
                return 0;
            }
        }
    }
}
