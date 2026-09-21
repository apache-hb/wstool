package wstool.cli;

import picocli.CommandLine.Command;

@Command(
        name = "distro",
        description = "Build and release a distribution.",
        mixinStandardHelpOptions = true,
        subcommands = {DistroBuildCommand.class, DistroReleaseCommand.class})
public final class DistroCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("Use distro build or release.");
    }
}
