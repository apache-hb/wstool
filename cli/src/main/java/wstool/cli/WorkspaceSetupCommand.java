package wstool.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.WorkspaceLayout;

@Command(
        name = "setup",
        description = "Select the workspace distribution.",
        mixinStandardHelpOptions = true)
public final class WorkspaceSetupCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(WorkspaceSetupCommand.class);

    @Option(names = "--distro", required = true)
    String distro;

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        var distribution = layout.distribution();
        if (!distro.equals(distribution.name())) {
            throw new IllegalArgumentException(
                    "Distribution name is " + distribution.name() + ", but --distro was " + distro);
        }
        LOG.info("Configured distribution {} from {}", distribution.name(), layout.worldFile());
        return 0;
    }
}
