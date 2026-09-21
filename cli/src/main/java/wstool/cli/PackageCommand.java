package wstool.cli;

import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import wstool.common.BuildService;
import wstool.common.PackageDescriptorReader;
import wstool.common.WorkspaceLayout;

@Command(name = "package", description = "Build a package.", mixinStandardHelpOptions = true)
public final class PackageCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(PackageCommand.class);
    private final BuildService buildService;

    @Inject
    public PackageCommand(BuildService buildService) {
        this.buildService = buildService;
    }

    @Parameters(index = "0", description = "Build target, or release.", defaultValue = "release")
    String target;

    @Parameters(index = "1..", description = "Arguments forwarded to the build tool.")
    List<String> arguments;

    @Option(names = "--docker")
    boolean docker;

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        var packageDirectory = findPackageDirectory(Path.of("."));
        var descriptor = PackageDescriptorReader.read(packageDirectory);
        if (!target.equals("release")) {
            buildService.runTarget(
                    layout, descriptor, target, arguments == null ? List.of() : arguments, docker);
            return 0;
        }
        buildService.release(layout, descriptor, docker);
        LOG.info("Released {}", descriptor.version());
        return 0;
    }

    private static Path findPackageDirectory(Path start) {
        var path = start.toAbsolutePath().normalize();
        while (path != null) {
            if (Files.exists(path.resolve("wstool.pkl"))) return path;
            path = path.getParent();
        }
        throw new IllegalStateException("Not inside a package checkout");
    }
}
