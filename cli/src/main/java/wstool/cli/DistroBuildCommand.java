package wstool.cli;

import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.BuildService;
import wstool.common.WorkspaceLayout;

@Command(
        name = "build",
        description = "Build all packages in dependency order.",
        mixinStandardHelpOptions = true)
public final class DistroBuildCommand implements Callable<Integer> {
    @Inject BuildService buildService;

    @Option(names = "--docker", description = "Run package builds in the selected Docker image.")
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
