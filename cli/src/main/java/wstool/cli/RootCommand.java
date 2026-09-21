package wstool.cli;

import com.google.inject.Guice;
import com.google.inject.Injector;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import wstool.common.WstoolModule;

@Command(
        name = "wstool",
        description = "Workspace tooling.",
        mixinStandardHelpOptions = true,
        version = "wstool " + RootCommand.VERSION,
        subcommands = {
            WorkspaceCommand.class,
            PackageCommand.class,
            DistroCommand.class,
            SysrootCommand.class,
            AggregateCommand.class
        })
public final class RootCommand implements Callable<Integer> {
    static final String VERSION = "0.1.0";

    @Override
    public Integer call() {
        return 0;
    }

    public static void main(String[] args) {
        Injector injector = Guice.createInjector(new WstoolModule());
        var defaultFactory = CommandLine.defaultFactory();
        var factory =
                new CommandLine.IFactory() {
                    @Override
                    public <K> K create(Class<K> cls) {
                        if (cls == PackageCommand.class
                                || cls == SysrootCommand.class
                                || cls == AggregateCommand.class
                                || cls == DistroBuildCommand.class) {
                            return injector.getInstance(cls);
                        }
                        try {
                            return defaultFactory.create(cls);
                        } catch (Exception e) {
                            throw new IllegalStateException(
                                    "Unable to create command " + cls.getName(), e);
                        }
                    }
                };
        var commandLine = new CommandLine(injector.getInstance(RootCommand.class), factory);
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
