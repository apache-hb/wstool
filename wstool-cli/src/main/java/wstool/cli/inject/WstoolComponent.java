package wstool.cli.inject;

import dagger.BindsInstance;
import dagger.Component;
import java.util.Map;
import javax.inject.Singleton;
import wstool.common.pkl.PklHelper;

/** The application dependency graph for the CLI. */
@Singleton
@Component(modules = WstoolModule.class)
public interface WstoolComponent {
    PklHelper pklHelper();

    DaggerCommandFactory commandFactory();

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder env(Map<String, String> env);

        @BindsInstance
        Builder interactive(boolean interactive);

        WstoolComponent build();
    }
}
