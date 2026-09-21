package wstool.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.WorkspaceLayout;

@Command(
        name = "setup",
        description = "Select the workspace distribution.",
        mixinStandardHelpOptions = true)
public final class WorkspaceSetupCommand implements Callable<Integer> {
    @Option(names = "--distro", required = true)
    String distro;

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        var properties = layout.state();
        properties.setProperty("distro", distro);
        layout.saveState(properties);
        System.out.println("Configured distribution " + distro);
        return 0;
    }
}
