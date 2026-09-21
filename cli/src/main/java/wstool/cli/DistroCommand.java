package wstool.cli;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;

@Command(
        name = "distro",
        description = "Build and release a distribution.",
        mixinStandardHelpOptions = true,
        subcommands = {DistroBuildCommand.class, DistroReleaseCommand.class})
public final class DistroCommand implements Runnable {
    private static final Logger LOG = LogManager.getLogger(DistroCommand.class);

    @Override
    public void run() {
        LOG.info("Use distro build or release.");
    }
}
