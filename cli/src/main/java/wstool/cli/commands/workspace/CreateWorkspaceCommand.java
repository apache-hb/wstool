package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.simple.GitHelper;
import wstool.simple.WorkspaceHelper;
import wstool.simple.git.GitRepositoryLocation;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.workspace.ImmutableCreateWorkspaceInput;

@Command(
        name = "create",
        description = "Create a workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class CreateWorkspaceCommand extends CommonCommand {
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

    private final GitHelper git;
    private final WorkspaceHelper workspace;

    @Inject
    public CreateWorkspaceCommand(GitHelper git, WorkspaceHelper workspace) {
        this.git = git;
        this.workspace = workspace;
    }

    @Override
    public Integer call() throws Exception {
        git.setInteractive(interactive);
        var input = ImmutableCreateWorkspaceInput.builder().parent(cwd.toAbsolutePath()).name(name);

        if (distribution != null) {
            URI source =
                    distribution.getScheme() == null
                            ? Path.of(distribution.getPath()).toAbsolutePath().normalize().toUri()
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
    }
}
