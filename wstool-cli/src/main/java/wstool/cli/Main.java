package wstool.cli;

import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import wstool.cli.commands.BuildCommand;
import wstool.cli.commands.SelectCommand;
import wstool.cli.commands.packages.PackageCommand;
import wstool.cli.commands.workspace.WorkspaceCommand;
import wstool.cli.inject.DaggerWstoolComponent;
import wstool.cli.inject.WstoolComponent;
import wstool.common.pkl.PklHelper;

@Command(
        name = "wstool",
        description = "Manage workspaces and packages.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
            WorkspaceCommand.class,
            PackageCommand.class,
            SelectCommand.class,
            BuildCommand.class
        })
public class Main {
    public static void main(String[] args) throws Exception {
        WstoolComponent component =
                DaggerWstoolComponent.builder().env(Map.of()).interactive(true).build();

        System.exit(runCommandLine(component, args));
    }

    private static int runCommandLine(WstoolComponent component, String[] args) throws Exception {
        try (PklHelper _ = component.pklHelper()) {
            return new CommandLine(new Main(), component.commandFactory()).execute(args);
        }
    }
}
