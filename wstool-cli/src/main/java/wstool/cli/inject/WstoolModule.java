package wstool.cli.inject;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import java.net.http.HttpClient;
import java.util.Map;
import javax.inject.Singleton;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import wstool.cli.commands.CommonCommand;
import wstool.cli.commands.SelectCommand;
import wstool.cli.commands.packages.PackageCommand;
import wstool.cli.commands.packages.RunTargetCommand;
import wstool.cli.commands.workspace.AddPackageCommand;
import wstool.cli.commands.workspace.CreateWorkspaceCommand;
import wstool.cli.commands.workspace.ExecuteRecursiveCommand;
import wstool.cli.commands.workspace.RemovePackageCommand;
import wstool.common.pkl.PklHelper;
import wstool.git.GitClient;

/** Configures the application-scoped services used by the CLI. */
@Module
public final class WstoolModule {
    @Provides
    @Singleton
    static ArchiveStreamFactory provideArchiveStreamFactory() {
        return new ArchiveStreamFactory();
    }

    @Provides
    @Singleton
    static HttpClient provideHttpClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Provides
    @Singleton
    static PklHelper providePklHelper(Map<String, String> env) {
        return new PklHelper(env);
    }

    @Provides
    @Singleton
    static GitClient provideGitClient(boolean interactive) {
        return new GitClient(interactive);
    }

    @Provides
    @IntoMap
    @ClassKey(AddPackageCommand.class)
    static CommonCommand provideAddPackageCommand(AddPackageCommand command) {
        return command;
    }

    @Provides
    @IntoMap
    @ClassKey(CreateWorkspaceCommand.class)
    static CommonCommand provideCreateWorkspaceCommand(CreateWorkspaceCommand command) {
        return command;
    }

    @Provides
    @IntoMap
    @ClassKey(RemovePackageCommand.class)
    static CommonCommand provideRemovePackageCommand(RemovePackageCommand command) {
        return command;
    }

    @Provides
    @IntoMap
    @ClassKey(SelectCommand.class)
    static CommonCommand provideSelectCommand(SelectCommand command) {
        return command;
    }

    @Provides
    @IntoMap
    @ClassKey(RunTargetCommand.class)
    static CommonCommand provideRunTargetCommand(RunTargetCommand command) {
        return command;
    }

    @Provides
    @IntoMap
    @ClassKey(PackageCommand.class)
    static CommonCommand providePackageCommand(PackageCommand command) {
        return command;
    }

    @Provides
    @IntoMap
    @ClassKey(ExecuteRecursiveCommand.class)
    static CommonCommand provideExecuteRecursiveCommand(ExecuteRecursiveCommand command) {
        return command;
    }
}
