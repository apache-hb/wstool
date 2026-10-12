package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import java.net.URI;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.simple.Workspace;
import wstool.simple.WorkspaceFactory;
import wstool.simple.WorkspaceHelper;
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

    private final WorkspaceHelper workspaceHelper;
    private final WorkspaceFactory workspaceFactory;

    @Inject
    public SetupWorkspaceCommand(
            WorkspaceHelper workspaceHelper, WorkspaceFactory workspaceFactory) {
        this.workspaceHelper = workspaceHelper;
        this.workspaceFactory = workspaceFactory;
    }

    @Override
    public Integer call() throws Exception {
        try (Workspace workspace = workspaceFactory.findUp(cwd)) {
            var distribution =
                    ImmutableGitRepositoryLocation.builder()
                            .repository(repository)
                            .branch(branch)
                            .build();

            workspaceHelper.setup(workspace, distribution);
            return 0;
        }
    }
}
