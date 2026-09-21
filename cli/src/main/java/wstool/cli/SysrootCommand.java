package wstool.cli;

import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import wstool.common.BuildService;
import wstool.common.PackageDescriptorReader;
import wstool.common.WorkspaceLayout;

@Command(
        name = "sysroot",
        description = "Materialize dependency files into the workspace sysroot.",
        mixinStandardHelpOptions = true)
public final class SysrootCommand implements Callable<Integer> {
    @Inject BuildService buildService;

    @Parameters(
            index = "0",
            description = "build, development, runtime, or package@version/selector")
    String selector;

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        var packageDirectory = Path.of(".").toAbsolutePath().normalize();
        while (packageDirectory != null
                && !Files.exists(packageDirectory.resolve("wstool.pkl"))
                && !Files.exists(packageDirectory.resolve("Package.pkl")))
            packageDirectory = packageDirectory.getParent();
        if (packageDirectory == null)
            throw new IllegalStateException("Not inside a package checkout");
        var descriptor = PackageDescriptorReader.read(packageDirectory);
        var normalizedSelector = selector;
        var slash = selector.indexOf('/');
        if (slash >= 0) normalizedSelector = selector.substring(slash + 1);
        buildService.materializeDependencies(layout, descriptor, normalizedSelector);
        System.out.println("Materialized sysroot selector " + selector + " at " + layout.sysroot());
        return 0;
    }
}
