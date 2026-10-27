package wstool.cli.commands.packages;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommonCommand;

@Command(
        name = "package",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {RunTargetCommand.class, StorePackageCommand.class})
public class PackageCommand extends CommonCommand {
    @Unmatched private List<String> targetArguments = new ArrayList<>();

    @Inject
    public PackageCommand() {}

    @Override
    public Integer call() throws Exception {
        return 0;
    }
}
