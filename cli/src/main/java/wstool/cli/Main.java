package wstool.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import wstool.cli.commands.BuildCommand;
import wstool.cli.commands.SysrootCommand;
import wstool.cli.commands.packages.PackageCommand;
import wstool.cli.commands.workspace.WorkspaceCommand;

@Command(
        name = "wstool",
        description = "Manage workspaces and packages.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
            WorkspaceCommand.class,
            PackageCommand.class,
            SysrootCommand.class,
            BuildCommand.class
        })
public class Main {
    public static void main(String[] args) {
        System.exit(new CommandLine(Main.class).execute(args));
    }
}
