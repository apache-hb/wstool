package wstool.cli.commands.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommandHelpers;
import wstool.simple.GitHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
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
public class AddPackageCommand implements Callable<Integer> {
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

    @Override
    public Integer call() {
        try (PklHelper pkl = new PklHelper(null)) {
            GitHelper git = new GitHelper(interactive);
            StorageHelper storage = new StorageHelper();
            WorkspaceHelper workspaceHelper = CommandHelpers.workspace(git, storage, pkl);
            WorkspaceFactory workspaceFactory = new WorkspaceFactory(pkl);
            Workspace workspace = workspaceFactory.findUp(Path.of(""));

            workspaceHelper.refresh(workspace);

            for (String packageName : packageNames) {
                AddPackageToWorkspaceInput input =
                        ImmutableAddPackageToWorkspaceInput.builder()
                                .workspace(workspace.path())
                                .name(packageName)
                                .version(packageVersion)
                                .build();

                workspaceHelper.addPackageToWorkspace(input);
            }

            return 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
