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
import wstool.simple.git.GitRepositoryLocation;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.workspace.CreateWorkspaceInput;
import wstool.simple.workspace.ImmutableCreateWorkspaceInput;

@Command(name = "create", description = "Create a workspace.")
public class CreateWorkspaceCommand implements Callable<Integer> {
    @Option(
            names = {"--name"},
            required = true)
    String name;

    @Option(
            names = {"--distribution", "-d"},
            required = true)
    URI distribution;

    @Option(names = {"--branch"})
    String branch;

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
            DistributionHelper dist = new DistributionHelper(pkl);
            WorkspaceHelper workspace = new WorkspaceHelper(git, storage, dist);

            GitRepositoryLocation location =
                    ImmutableGitRepositoryLocation.builder()
                            .branch(branch)
                            .repository(distribution)
                            .build();

            CreateWorkspaceInput input =
                    ImmutableCreateWorkspaceInput.builder()
                            .parent(Path.of("").toAbsolutePath())
                            .name(name)
                            .distribution(location)
                            .build();

            workspace.createWorkspace(input);

            return 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
