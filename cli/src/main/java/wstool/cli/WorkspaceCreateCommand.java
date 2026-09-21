package wstool.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.WorkspaceLayout;

@Command(
        name = "create",
        description = "Create a workspace directory.",
        mixinStandardHelpOptions = true)
public final class WorkspaceCreateCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(WorkspaceCreateCommand.class);

    @Option(names = "--name", required = true)
    String name;

    @Option(names = "--root", description = "Parent directory (defaults to the current directory).")
    Path parent;

    @Override
    public Integer call() {
        var base = parent == null ? Path.of(".") : parent;
        var layout = WorkspaceLayout.create(base.resolve(name));
        var properties = layout.state();
        properties.setProperty("name", name);
        layout.saveState(properties);
        LOG.info("Created workspace {}", layout.root());
        return 0;
    }
}
