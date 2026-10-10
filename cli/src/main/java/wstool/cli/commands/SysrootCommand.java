package wstool.cli.commands;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import wstool.Package;
import wstool.cli.VersionProvider;
import wstool.simple.DependencyResolver;
import wstool.simple.DistributionHelper;
import wstool.simple.GitHelper;
import wstool.simple.PackageHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.SysrootManager;
import wstool.simple.SysrootManager.SysrootTarget;
import wstool.simple.WorkspaceHelper;

@Command(
        name = "sysroot",
        description = "Create a sysroot from workspace package artifacts.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class SysrootCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Selector, optionally prefixed by package@version/")
    private String selector;

    @Option(
            names = "--output",
            description = "Sysroot directory (defaults to the workspace sysroot directory).")
    private Path output;

    @Override
    public Integer call() throws Exception {
        Path source = Path.of("").toAbsolutePath().normalize();
        try (PklHelper pkl = new PklHelper(null)) {
            Package config = new PackageHelper(pkl).getPackageConfig(source.resolve("wstool.pkl"));
            WorkspaceHelper workspace =
                    new WorkspaceHelper(
                            new GitHelper(false), new StorageHelper(), new DistributionHelper(pkl));
            Path workspaceRoot = workspace.findWorkspaceUp(source);
            String privateDirectory = System.getenv("WST_PRIVATE");
            if (privateDirectory != null && !privateDirectory.isBlank()) {
                Path sysroot =
                        output == null ? Path.of(privateDirectory).resolve("sysroot") : output;
                new DependencyResolver()
                        .materializeClosure(sysroot, workspace.artifactPackages(workspaceRoot));
                return 0;
            }
            SysrootTarget target = parseTarget(selector);
            Path sysroot = output;
            new SysrootManager()
                    .createSysroot(
                            sysroot,
                            workspace,
                            workspaceRoot,
                            config.getPackage().getName(),
                            target);
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
