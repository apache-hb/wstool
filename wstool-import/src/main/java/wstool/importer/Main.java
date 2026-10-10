package wstool.importer;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import wstool.npm.importer.NpmImportCommand;

@Command(
        name = "wstool-import",
        description = "Import Maven and npm packages into wstool packages.",
        mixinStandardHelpOptions = true,
        versionProvider = ImporterVersionProvider.class,
        subcommands = {MavenImportCommand.class, NpmImportCommand.class})
public class Main {
    public static void main(String[] args) {
        System.exit(new CommandLine(Main.class).execute(args));
    }
}
