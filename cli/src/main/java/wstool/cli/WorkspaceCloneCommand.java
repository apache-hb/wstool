package wstool.cli;

import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.WorkspaceCloneService;
import wstool.common.WorkspaceLayout;

@Command(
        name = "clone",
        description = "Clone a local package repository into the workspace.",
        mixinStandardHelpOptions = true)
public final class WorkspaceCloneCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(WorkspaceCloneCommand.class);

    private final WorkspaceCloneService cloneService;

    @Option(names = "--package", required = true, arity = "1..")
    List<String> packages;

    @Option(
            names = "--from",
            description = "Local repository directory or git URL; may be used once per package.")
    String from;

    @Inject
    public WorkspaceCloneCommand(WorkspaceCloneService cloneService) {
        this.cloneService = cloneService;
    }

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        cloneService.clonePackages(layout, packages, from);
        packages.forEach(packageName -> LOG.info("Cloned {}", packageName));
        return 0;
    }
}
