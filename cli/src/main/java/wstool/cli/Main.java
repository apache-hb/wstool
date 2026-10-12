package wstool.cli;

import java.util.Map;

import com.google.inject.Guice;
import com.google.inject.Injector;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import wstool.cli.commands.BuildCommand;
import wstool.cli.commands.SysrootCommand;
import wstool.cli.commands.packages.PackageCommand;
import wstool.cli.commands.workspace.WorkspaceCommand;
import wstool.cli.inject.GuiceCommandFactory;
import wstool.cli.inject.WstoolModule;
import wstool.simple.PklHelper;

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
    public static void main(String[] args) throws Exception {
        Injector injector = Guice.createInjector(new WstoolModule(Map.of()));

        System.exit(runCommandLine(injector, args));
    }

    private static int runCommandLine(Injector injector, String[] args) throws Exception {
        try (PklHelper _ = injector.getInstance(PklHelper.class)) {
            return new CommandLine(new Main(), new GuiceCommandFactory(injector)).execute(args);
        }
    }
}
