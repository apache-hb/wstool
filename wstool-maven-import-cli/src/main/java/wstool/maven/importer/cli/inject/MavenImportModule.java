package wstool.maven.importer.cli.inject;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import org.eclipse.aether.supplier.RepositorySystemSupplier;

@Module
public final class MavenImportModule {
    private MavenImportModule() {}

    @Provides
    @Singleton
    static RepositorySystemSupplier provideRepositorySystemSupplier() {
        return new RepositorySystemSupplier();
    }
}
