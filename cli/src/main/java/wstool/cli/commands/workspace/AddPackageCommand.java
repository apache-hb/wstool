package wstool.cli.commands.workspace;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;
import wstool.simple.GitHelper;
import wstool.simple.Workspace;
import wstool.simple.WorkspaceFactory;
import wstool.simple.WorkspaceHelper;
import wstool.simple.workspace.AddPackageToWorkspaceInput;
import wstool.simple.workspace.ImmutableAddPackageToWorkspaceInput;

@Command(
        name = "add",
        aliases = "clone",
        description = "Add a package to the workspace.",
        versionProvider = VersionProvider.class)
public class AddPackageCommand extends CommonCommand {
    @Option(
            names = {"-h", "--help"},
            usageHelp = true)
    boolean helpRequested;

    @Option(names = "-V", versionHelp = true)
    boolean versionRequested;

    @Option(
            names = {"--package", "-p"},
            required = true)
    List<String> packageNames = new ArrayList<>();

    @Option(
            names = {"--version"},
            required = false)
    String packageVersion;

    @Option(
            names = {"--interactive"},
            defaultValue = "true")
    boolean interactive;

    private final GitHelper git;
    private final WorkspaceHelper workspaceHelper;
    private final WorkspaceFactory workspaceFactory;

    @Inject
    public AddPackageCommand(
            GitHelper git, WorkspaceHelper workspaceHelper, WorkspaceFactory workspaceFactory) {
        this.git = git;
        this.workspaceHelper = workspaceHelper;
        this.workspaceFactory = workspaceFactory;
    }

    @Override
    public Integer call() {
        git.setInteractive(interactive);
        try (Workspace workspace = workspaceFactory.findUp(cwd)) {
            workspace.refresh();

            for (String packageName : packageNames) {
                AddPackageToWorkspaceInput input =
                        ImmutableAddPackageToWorkspaceInput.builder()
                                .workspace(workspace.path())
                                .name(packageName)
                                .version(packageVersion)
                                .build();

                workspaceHelper.addPackageToWorkspace(input);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }
}
