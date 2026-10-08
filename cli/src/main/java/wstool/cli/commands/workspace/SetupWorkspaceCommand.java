package wstool.cli.commands.workspace;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.simple.DistributionHelper;
import wstool.simple.GitHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.WorkspaceHelper;
import wstool.simple.git.ImmutableGitRepositoryLocation;

@Command(name = "setup", description = "Set the distribution for this workspace.")
public class SetupWorkspaceCommand implements Callable<Integer> {
    @Option(names = "--distro", required = true, description = "Distribution name.")
    private String distroName;

    @Option(names = "--from", required = true, description = "Distribution Git URL or local path.")
    private URI repository;

    @Option(names = "--branch", description = "Git branch to clone.")
    private String branch;

    @Override
    public Integer call() throws Exception {
        try (PklHelper pkl = new PklHelper(null)) {
            WorkspaceHelper workspace =
                    new WorkspaceHelper(
                            new GitHelper(false), new StorageHelper(), new DistributionHelper(pkl));
            Path root = workspace.findWorkspaceUp(Path.of(""));
            URI source =
                    repository.getScheme() == null
                            ? Path.of(repository.getPath()).toAbsolutePath().normalize().toUri()
                            : repository;
            workspace.setupDistribution(
                    root,
                    ImmutableGitRepositoryLocation.builder()
                            .repository(source)
                            .branch(branch)
                            .build());
            String actualName =
                    new DistributionHelper(pkl)
                            .getDistributionConfig(workspace.workspaceDistributionConfig(root))
                            .getName();
            if (!distroName.equals(actualName)) {
                throw new IllegalArgumentException(
                        "Distribution name '"
                                + actualName
                                + "' does not match '"
                                + distroName
                                + "'.");
            }
            return 0;
        }
    }
}
