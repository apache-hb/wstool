package wstool.common;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import java.nio.file.Path;
import wstool.Distribution;

public final class WstoolModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(ArtifactStore.class).to(S3ArtifactStore.class).in(Scopes.SINGLETON);
        bind(BuildService.class).in(Scopes.SINGLETON);
        bind(WorkspaceCloneService.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    Distribution.Storage workspaceStorage() {
        return WorkspaceLayout.find(Path.of(".")).distribution().storage();
    }
}
