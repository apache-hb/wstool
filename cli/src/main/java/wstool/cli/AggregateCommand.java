package wstool.cli;

import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import wstool.common.BuildService;
import wstool.common.PackageDescriptorReader;
import wstool.common.WorkspaceLayout;

@Command(
        name = "aggregate",
        description = "Create an aggregate tree from a sysroot selector.",
        mixinStandardHelpOptions = true)
public final class AggregateCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(AggregateCommand.class);
    private final BuildService buildService;

    @Inject
    public AggregateCommand(BuildService buildService) {
        this.buildService = buildService;
    }

    @Parameters(
            index = "0",
            description = "build, development, runtime, or package@version/selector")
    String selector;

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        var packageDirectory = Path.of(".").toAbsolutePath().normalize();
        while (packageDirectory != null && !Files.exists(packageDirectory.resolve("wstool.pkl")))
            packageDirectory = packageDirectory.getParent();
        if (packageDirectory == null)
            throw new IllegalStateException("Not inside a package checkout");
        var descriptor = PackageDescriptorReader.read(packageDirectory);
        var normalizedSelector = selector;
        var slash = selector.indexOf('/');
        if (slash >= 0) normalizedSelector = selector.substring(slash + 1);
        buildService.materializeDependencies(layout, descriptor, normalizedSelector);
        var aggregate =
                layout.metadata()
                        .resolve("aggregate")
                        .resolve(descriptor.version().name() + "-" + normalizedSelector);
        copy(layout.sysroot(), aggregate);
        LOG.info("Created aggregate at {}", aggregate);
        return 0;
    }

    private static void copy(Path source, Path destination) {
        try (var paths = Files.walk(source)) {
            paths.forEach(
                    path -> {
                        var target = destination.resolve(source.relativize(path).toString());
                        try {
                            if (Files.isDirectory(path)) Files.createDirectories(target);
                            else {
                                Files.createDirectories(target.getParent());
                                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
