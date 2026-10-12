package wstool.cli.inject;

import com.google.inject.Binder;
import com.google.inject.Module;
import java.util.Map;

/** Configures the application-scoped services used by the CLI. */
public record WstoolModule(Map<String, String> env) implements Module {
    @Override
    public void configure(Binder binder) {}
}
