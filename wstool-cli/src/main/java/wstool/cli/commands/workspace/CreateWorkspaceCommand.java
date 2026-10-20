package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import java.net.URI;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.workspace.WorkspaceFactory;
import wstool.git.model.ImmutableGitRepositoryLocation;

@Command(
        name = "create",
        description = "Create a workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class CreateWorkspaceCommand extends CommonCommand {
    @Option(
            names = {"--name", "-n"},
            description = "The name of the workspace.",
            required = true)
    String name;

    @Option(
            names = {"--distribution", "-d"},
            description = "The distribution to use for the workspace.",
            required = true)
    URI distribution;

    @Option(
            names = {"--branch", "-b"},
            description = "The branch of the distribution to use for the workspace.",
            required = false)
    String branch = "master";

    private final WorkspaceFactory workspaceFactory;

    @Inject
    public CreateWorkspaceCommand(WorkspaceFactory workspaceFactory) {
        this.workspaceFactory = workspaceFactory;
    }

    @Override
    public Integer call() throws Exception {
        var distributionLocation =
                ImmutableGitRepositoryLocation.builder()
                        .repository(distribution)
                        .branch(branch)
                        .build();

        workspaceFactory.setup(cwd.resolve(name), distributionLocation);
        return 0;
    }
}
