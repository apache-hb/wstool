package wstool.cli.commands.workspace;

import javax.inject.Inject;
import picocli.CommandLine.Command;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "refresh",
        description = "Refresh workspace data from the distribution config.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RefreshWorkspaceCommand extends CommonCommand {
    private final WorkspaceFactory workspaceFactory;

    @Inject
    public RefreshWorkspaceCommand(WorkspaceFactory workspaceFactory) {
        this.workspaceFactory = workspaceFactory;
    }

    @Override
    public Integer call() throws Exception {
        workspaceFactory.findUp(cwd).refresh();
        return 0;
    }
}
