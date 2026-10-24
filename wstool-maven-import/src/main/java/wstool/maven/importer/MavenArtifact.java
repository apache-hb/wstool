package wstool.maven.importer;

import java.nio.file.Path;
import java.util.List;

/** A resolved Maven artifact and its direct dependencies. */
public record MavenArtifact(String coordinate, Path file, List<String> dependencies) {
    public MavenArtifact {
        dependencies = List.copyOf(dependencies);
    }
}
