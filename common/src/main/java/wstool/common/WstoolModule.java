package wstool.common;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

public final class WstoolModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(ArtifactStore.class).to(S3ArtifactStore.class).in(Scopes.SINGLETON);
        bind(BuildService.class).in(Scopes.SINGLETON);
    }
}
