package wstool.maven.importer.cli.inject;

import dagger.BindsInstance;
import dagger.Component;
import java.nio.file.Path;
import javax.inject.Singleton;
import wstool.maven.importer.MavenImporter;

@Singleton
@Component(modules = MavenImportModule.class)
public interface MavenImportComponent {
    MavenImporter mavenImporter();

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder repositoryUrl(String repositoryUrl);

        @BindsInstance
        Builder localRepository(Path localRepository);

        MavenImportComponent build();
    }
}
