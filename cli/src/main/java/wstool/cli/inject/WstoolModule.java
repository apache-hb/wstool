package wstool.cli.inject;

import java.util.Map;

import com.google.inject.Binder;
import com.google.inject.Module;

import wstool.common.pkl.PklHelper;
import wstool.git.GitClient;

/** Configures the application-scoped services used by the CLI. */
public record WstoolModule(Map<String, String> env, boolean interactive) implements Module {
    @Override
    public void configure(Binder binder) {
        binder.bind(PklHelper.class).toInstance(new PklHelper(env));
        binder.bind(GitClient.class).toInstance(new GitClient(interactive));
    }
}
