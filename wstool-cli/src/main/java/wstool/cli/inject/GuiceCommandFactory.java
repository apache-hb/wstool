package wstool.cli.inject;

import com.google.inject.Injector;
import picocli.CommandLine.IFactory;

/** Creates Picocli commands through the application injector. */
public final class GuiceCommandFactory implements IFactory {
    private final Injector injector;

    public GuiceCommandFactory(Injector injector) {
        this.injector = injector;
    }

    @Override
    public <K> K create(Class<K> commandClass) throws Exception {
        return injector.getInstance(commandClass);
    }
}
