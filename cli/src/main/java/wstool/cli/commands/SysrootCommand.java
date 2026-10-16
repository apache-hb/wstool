package wstool.cli.commands;

import jakarta.inject.Inject;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import wstool.cli.VersionProvider;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageFactory;
import wstool.common.path.PathBuilder;
import wstool.common.workspace.LocalWorkspace;
import wstool.common.workspace.WorkspaceFactory;

@Command(
        name = "sysroot",
        description = "Create a sysroot from workspace package artifacts.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class SysrootCommand extends CommonCommand {
    @Parameters(
            index = "0",
            description =
                    "Sysroot selector: build, development, runtime, or <name>@<version>/<selector>.")
    private String selector;

    @Option(
            names = "--output",
            description = "Directory to create the sysroot in.",
            required = true)
    private Path output;

    private final WorkspaceFactory workspaceFactory;
    private final PackageFactory packageFactory;
    private final PathBuilder pathBuilder;

    @Inject
    public SysrootCommand(
            WorkspaceFactory workspaceFactory,
            PackageFactory packageFactory,
            PathBuilder pathBuilder) {
        this.workspaceFactory = workspaceFactory;
        this.packageFactory = packageFactory;
        this.pathBuilder = pathBuilder;
    }

    @Override
    public Integer call() throws Exception {
        LocalWorkspace workspace = workspaceFactory.findUp(cwd);
        workspace.refresh();

        PackageMajorVersion root = packageFactory.findUp(cwd).name();
        String mode = selector;
        int separator = selector.lastIndexOf('/');
        if (separator >= 0) {
            String packageSelector = selector.substring(0, separator);
            mode = selector.substring(separator + 1);
            int versionSeparator = packageSelector.lastIndexOf('@');
            if (versionSeparator <= 0 || versionSeparator == packageSelector.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid package selector '"
                                + selector
                                + "'. Expected <name>@<version>/<selector>.");
            }
            root =
                    new PackageMajorVersion(
                            packageSelector.substring(0, versionSeparator),
                            packageSelector.substring(versionSeparator + 1));
        }

        if (!mode.equals("build") && !mode.equals("development") && !mode.equals("runtime")) {
            throw new IllegalArgumentException(
                    "Invalid sysroot selector '"
                            + mode
                            + "'. Expected build, development, or runtime.");
        }

        pathBuilder.sysrootSymlinkFolder(workspace, root, mode, output);
        return 0;
    }
}
