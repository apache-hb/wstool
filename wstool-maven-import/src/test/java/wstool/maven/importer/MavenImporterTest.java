package wstool.maven.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.test.components.TestResources;

class MavenImporterTest {
    @TempDir Path tempDirectory;

    @Test
    void resolvesRootAndTransitiveArtifactsFromRepository() throws Exception {
        MavenImporter importer = newImporter();

        List<MavenArtifact> artifacts = importer.resolve(List.of("test.import-fixture:root:1.0"));

        assertEquals(2, artifacts.size());
        MavenArtifact root = artifactNamed(artifacts, "root-1.0.jar");
        MavenArtifact leaf = artifactNamed(artifacts, "leaf-2.0.jar");
        assertTrue(root.file().toFile().isFile());
        assertTrue(leaf.file().toFile().isFile());
        assertEquals(List.of(leaf.coordinate()), root.dependencies());
        assertEquals(List.of(), leaf.dependencies());
    }

    @Test
    void rejectsAnEmptyCoordinateList() throws Exception {
        MavenImporter importer = newImporter();

        assertThrows(IllegalArgumentException.class, () -> importer.resolve(List.of()));
    }

    @Test
    void failsWhenAnArtifactIsMissingFromTheRepository() throws Exception {
        MavenImporter importer = newImporter();

        assertThrows(
                DependencyResolutionException.class,
                () -> importer.resolve(List.of("test.import-fixture:missing:1.0")));
    }

    private MavenImporter newImporter() throws Exception {
        Path repository =
                TestResources.copyDirectory(
                        getClass(), "/maven-repository", tempDirectory.resolve("maven-repository"));
        return new MavenImporter(
                new RepositorySystemSupplier(),
                repository.toUri().toString(),
                tempDirectory.resolve("m2"));
    }

    private MavenArtifact artifactNamed(List<MavenArtifact> artifacts, String fileName) {
        return artifacts.stream()
                .filter(artifact -> artifact.file().getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow();
    }
}
