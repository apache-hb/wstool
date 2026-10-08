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
import wstool.simple.workspace.ImmutableCreateWorkspaceInput;

@Command(name = "create", description = "Create a workspace.")
public class CreateWorkspaceCommand implements Callable<Integer> {
    @Option(
            names = {"--name"},
            required = true)
    String name;

    @Option(names = {"--distribution", "-d"})
    URI distribution;

    @Option(names = {"--branch"})
    String branch;

    @Option(
            names = {"--interactive"},
            defaultValue = "true")
    boolean interactive;

    @Override
    public Integer call() {
        try (PklHelper pkl = new PklHelper(null)) {
            GitHelper git = new GitHelper(interactive);
            StorageHelper storage = new StorageHelper();
            DistributionHelper dist = new DistributionHelper(pkl);
            WorkspaceHelper workspace = new WorkspaceHelper(git, storage, dist);

            var input =
                    ImmutableCreateWorkspaceInput.builder()
                            .parent(Path.of("").toAbsolutePath())
                            .name(name);
            if (distribution != null) {
                URI source =
                        distribution.getScheme() == null
                                ? Path.of(distribution.getPath())
                                        .toAbsolutePath()
                                        .normalize()
                                        .toUri()
                                : distribution;
                GitRepositoryLocation location =
                        ImmutableGitRepositoryLocation.builder()
                                .branch(branch)
                                .repository(source)
                                .build();
                input.distribution(location);
            } else if (branch != null) {
                throw new IllegalArgumentException("--branch requires --distribution.");
            }
            workspace.createWorkspace(input.build());

            return 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
