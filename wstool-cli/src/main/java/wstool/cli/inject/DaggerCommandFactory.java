package wstool.cli.inject;

import java.util.Map;
import javax.inject.Inject;
import javax.inject.Provider;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;
import wstool.cli.commands.CommonCommand;

/** Creates Picocli commands through the application component where injection is required. */
public final class DaggerCommandFactory implements IFactory {
    private final Map<Class<?>, Provider<CommonCommand>> commands;

    @Inject
    public DaggerCommandFactory(Map<Class<?>, Provider<CommonCommand>> commands) {
        this.commands = commands;
    }

    @Override
    public <K> K create(Class<K> commandClass) throws Exception {
        Provider<CommonCommand> commandProvider = commands.get(commandClass);
        if (commandProvider != null) {
            return commandClass.cast(commandProvider.get());
        }
        return CommandLine.defaultFactory().create(commandClass);
    }
}
