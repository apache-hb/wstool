package wstool.maven.importer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;

/** Resolves Maven coordinates and their compile/runtime dependency graph. */
@Singleton
public final class MavenImporter {
    private final RepositorySystemSupplier repositorySystemSupplier;
    private final String repositoryUrl;
    private final Path localRepository;

    @Inject
    public MavenImporter(
            RepositorySystemSupplier repositorySystemSupplier,
            String repositoryUrl,
            Path localRepository) {
        this.repositorySystemSupplier = repositorySystemSupplier;
        this.repositoryUrl = repositoryUrl;
        this.localRepository = localRepository;
    }

    public List<MavenArtifact> resolve(List<String> coordinates)
            throws DependencyResolutionException {
        if (coordinates.isEmpty()) {
            throw new IllegalArgumentException("At least one Maven coordinate is required");
        }

        RepositorySystem system = repositorySystemSupplier.get();
        try {
            RepositorySystemSession session = newSession(system);
            RemoteRepository repository =
                    new RemoteRepository.Builder("import-repository", "default", repositoryUrl)
                            .build();
            List<Dependency> roots = new ArrayList<>();
            for (String coordinate : coordinates) {
                roots.add(new Dependency(new DefaultArtifact(coordinate), "compile"));
            }

            CollectRequest collectRequest =
                    new CollectRequest()
                            .setDependencies(roots)
                            .setRepositories(List.of(repository));
            DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
            DependencyResult result = system.resolveDependencies(session, dependencyRequest);
            Map<Artifact, DependencyNode> nodes = new LinkedHashMap<>();
            collectNodes(result.getRoot(), nodes);

            List<MavenArtifact> artifacts = new ArrayList<>();
            for (ArtifactResult artifactResult : result.getArtifactResults()) {
                Artifact artifact = artifactResult.getArtifact();
                DependencyNode node = nodes.get(artifact);
                List<String> dependencies =
                        node != null
                                ? node.getChildren().stream()
                                        .map(child -> child.getArtifact().toString())
                                        .toList()
                                : List.of();
                artifacts.add(
                        new MavenArtifact(
                                artifact.toString(), artifact.getFile().toPath(), dependencies));
            }
            return List.copyOf(artifacts);
        } finally {
            system.shutdown();
        }
    }

    private void collectNodes(DependencyNode node, Map<Artifact, DependencyNode> nodes) {
        // A CollectRequest with dependency roots has a synthetic graph root without an artifact.
        Artifact artifact = node.getArtifact();
        if (artifact != null) {
            nodes.put(artifact, node);
        }
        node.getChildren().forEach(child -> collectNodes(child, nodes));
    }

    private RepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        LocalRepository local = new LocalRepository(localRepository.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local));
        return session;
    }
}
