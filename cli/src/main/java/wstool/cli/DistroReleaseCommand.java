package wstool.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import wstool.common.WorkspaceLayout;

@Command(
        name = "release",
        description = "Mark the current distribution as released.",
        mixinStandardHelpOptions = true)
public final class DistroReleaseCommand implements Callable<Integer> {
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
        System.out.println(
                "Released distribution " + layout.state().getProperty("distro", "(unnamed)"));
        return 0;
    }
}
