package wstool.cli;

import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.BuildService;
import wstool.common.WorkspaceLayout;

@Command(
        name = "build",
        description = "Build all packages in dependency order.",
        mixinStandardHelpOptions = true)
public final class DistroBuildCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(DistroBuildCommand.class);
    private final BuildService buildService;

    @Inject
    public DistroBuildCommand(BuildService buildService) {
        this.buildService = buildService;
    }

    @Option(names = "--docker", description = "Run package builds in the selected Docker image.")
    boolean docker;

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        buildService.buildWorkspace(layout, docker);
        LOG.info("Built distribution {}", layout.state().getProperty("distro", "(unnamed)"));
        return 0;
    }
}
