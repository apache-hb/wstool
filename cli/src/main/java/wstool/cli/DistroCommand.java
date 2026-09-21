package wstool.cli;

import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.BuildService;
import wstool.common.WorkspaceLayout;

@Command(
        name = "distro",
        description = "Build and release a distribution.",
        mixinStandardHelpOptions = true,
        subcommands = {DistroCommand.Build.class, DistroCommand.Release.class})
public final class DistroCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("Use distro build or release.");
    }

    @Command(
            name = "build",
            description = "Build all packages in dependency order.",
            mixinStandardHelpOptions = true)
    public static final class Build implements Callable<Integer> {
        @Inject BuildService buildService;

        @Option(
                names = "--docker",
                description = "Run package builds in the selected Docker image.")
        boolean docker;

        @Option(names = "--build-image", defaultValue = "${WSTOOL_BUILD_IMAGE:-ubuntu:24.04}")
        String image;

        @Override
        public Integer call() {
            var layout = WorkspaceLayout.find(Path.of("."));
            buildService.buildWorkspace(layout, docker, image);
            System.out.println(
                    "Built distribution " + layout.state().getProperty("distro", "(unnamed)"));
            return 0;
        }
    }

    @Command(
            name = "release",
            description = "Mark the current distribution as released.",
            mixinStandardHelpOptions = true)
    public static final class Release implements Callable<Integer> {
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
}
