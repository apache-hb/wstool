package wstool.cli.commands;

import jakarta.inject.Inject;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import wstool.Package;
import wstool.cli.VersionProvider;
import wstool.simple.DependencyResolver;
import wstool.simple.PackageHelper;
import wstool.simple.SysrootManager;
import wstool.simple.SysrootManager.SysrootTarget;
import wstool.simple.Workspace;
import wstool.simple.WorkspaceFactory;
import wstool.simple.WorkspaceHelper;

@Command(
        name = "sysroot",
        description = "Create a sysroot from workspace package artifacts.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class SysrootCommand extends CommonCommand {
    @Parameters(index = "0", description = "Selector, optionally prefixed by package@version/")
    private String selector;

    @Option(
            names = "--output",
            description = "Sysroot directory (defaults to the workspace sysroot directory).")
    private Path output;

    private final PackageHelper packageHelper;
    private final WorkspaceHelper workspaceHelper;
    private final WorkspaceFactory workspaceFactory;
    private final SysrootManager sysrootManager;
    private final DependencyResolver dependencies;

    @Inject
    public SysrootCommand(
            PackageHelper packageHelper,
            WorkspaceHelper workspaceHelper,
            WorkspaceFactory workspaceFactory,
            SysrootManager sysrootManager,
            DependencyResolver dependencies) {
        this.packageHelper = packageHelper;
        this.workspaceHelper = workspaceHelper;
        this.workspaceFactory = workspaceFactory;
        this.sysrootManager = sysrootManager;
        this.dependencies = dependencies;
    }

    @Override
    public Integer call() throws Exception {
        try (Workspace workspace = workspaceFactory.findUp(cwd)) {
            String privateDirectory = System.getenv("WST_PRIVATE");
            Package config = packageHelper.getPackageConfig(cwd.resolve("wstool.pkl"));
            if (privateDirectory != null && !privateDirectory.isBlank()) {
                Path sysroot =
                        output == null ? Path.of(privateDirectory).resolve("sysroot") : output;
                dependencies.materializeClosure(
                        sysroot, workspaceHelper.artifactPackages(workspace.path()));
                return 0;
            }
            SysrootTarget target = parseTarget(selector);
            sysrootManager.createSysroot(
                    output, workspace.path(), config.getPackage().getName(), target);
            return 0;
        }
    }

    private SysrootTarget parseTarget(String value) {
        return switch (value) {
            case "build" -> SysrootTarget.BUILD;
            case "development" -> SysrootTarget.DEVELOPMENT;
            case "runtime" -> SysrootTarget.RUNTIME_DEPENDENCIES;
            case "test" -> SysrootTarget.TEST_DEPENDENCIES;
            default -> throw new IllegalArgumentException("Unknown sysroot selector: " + value);
        };
    }
}
