package wstool.cli.inject;

import com.google.inject.Binder;
import com.google.inject.Module;
import java.util.Map;
import wstool.simple.GitHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;

/** Configures the application-scoped services used by the CLI. */
public record WstoolModule(Map<String, String> env) implements Module {
    @Override
    public void configure(Binder binder) {
        binder.bind(PklHelper.class).toInstance(new PklHelper(env));
        binder.bind(GitHelper.class);
        binder.bind(StorageHelper.class);
    }
}
