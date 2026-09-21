package wstool.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import wstool.common.WorkspaceLayout;

@Command(
        name = "release",
        description = "Mark the current distribution as released.",
        mixinStandardHelpOptions = true)
public final class DistroReleaseCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(DistroReleaseCommand.class);

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        try {
            Files.writeString(
                    layout.metadata().resolve("release"),
                    layout.state().getProperty("distro", "(unnamed)"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOG.info("Released distribution {}", layout.state().getProperty("distro", "(unnamed)"));
        return 0;
    }
}
