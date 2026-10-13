package wstool.cli.commands.packages;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.common.workspace.LocalWorkspace;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "run",
        description = "Run a package build target.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class RunTargetCommand extends CommonCommand {
    @Unmatched List<String> args = new ArrayList<>();

    private final WorkspaceFactory workspaceFactory;

    @Inject
    public RunTargetCommand(WorkspaceFactory workspaceFactory) {
        this.workspaceFactory = workspaceFactory;
    }

    @Override
    public Integer call() throws Exception {
        LocalWorkspace workspace = workspaceFactory.findUp(cwd);
        workspace.refresh();
        return 0;
    }
}
