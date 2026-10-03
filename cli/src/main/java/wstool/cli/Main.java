package wstool.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import wstool.cli.commands.packages.PackageCommand;
import wstool.cli.commands.workspace.WorkspaceCommand;

@Command(
        name = "wstool",
        description = "Manage workspaces and packages.",
        subcommands = {WorkspaceCommand.class, PackageCommand.class})
public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(Main.class).execute(args);
        System.exit(exitCode);
    }
}
