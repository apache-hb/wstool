package wstool.cli.commands.workspace;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.workspace.GitHelper;
import wstool.workspace.LocalWorkspace;
import wstool.workspace.PklHelper;
import wstool.workspace.WorkspaceManager;

@Command(name = "create", description = "Create a workspace.")
public class CreateWorkspaceCommand implements Callable<Integer> {
    @Option(names = { "--name" }, required = true)
    String name;

    @Option(names = { "--distribution", "-d" }, required = true)
    URI distribution;

    @Option(names = { "--branch" })
    String branch;

    @Override
    public Integer call() {
        try {
            GitHelper git = new GitHelper(null, null);
            PklHelper pkl = new PklHelper(Map.of());
            WorkspaceManager manager = new WorkspaceManager(git, pkl);
            LocalWorkspace workspace = manager.setupWorkspace(Path.of(""), name, distribution, branch);
            return 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
