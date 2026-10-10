package wstool.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

/** Downloads the Maven dependency graph and its repository artifacts. */
public class MavenDependencyResolver {
    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final RemoteRepository repository;

    public MavenDependencyResolver(String repositoryUrl, Path localRepository) throws Exception {
        ServiceLocator locator =
                MavenRepositorySystemUtils.newServiceLocator()
                        .addService(
                                RepositoryConnectorFactory.class,
                                BasicRepositoryConnectorFactory.class)
                        .addService(TransporterFactory.class, FileTransporterFactory.class)
                        .addService(TransporterFactory.class, HttpTransporterFactory.class);
        system = locator.getService(RepositorySystem.class);
        if (system == null) {
            throw new IllegalStateException("Maven Resolver could not create RepositorySystem.");
        }
        Files.createDirectories(localRepository);
        var mutableSession = MavenRepositorySystemUtils.newSession();
        mutableSession.setLocalRepositoryManager(
                system.newLocalRepositoryManager(
                        mutableSession, new LocalRepository(localRepository.toFile())));
        session = mutableSession;
        repository =
                new RemoteRepository.Builder("wstool-import", "default", repositoryUrl).build();
    }

    public Map<MavenCoordinate, ResolvedArtifact> resolve(MavenCoordinate root) throws Exception {
        Path rootPom = resolveOptional(root, "", "pom");
        if (rootPom != null && resolveOptional(root, "", "jar") == null) {
            return resolveBom(root, rootPom);
        }
        Artifact rootArtifact =
                new DefaultArtifact(root.group(), root.artifact(), "", "jar", root.version());
        DependencyRequest request =
                new DependencyRequest(
                        new org.eclipse.aether.collection.CollectRequest(
                                new Dependency(rootArtifact, "compile"), List.of(repository)),
                        null);
        DependencyResult result = system.resolveDependencies(session, request);
        LinkedHashMap<MavenCoordinate, ResolvedArtifact> resolved = new LinkedHashMap<>();
        collect(result.getRoot(), null, resolved);

        for (var entry : new ArrayList<>(resolved.entrySet())) {
            MavenCoordinate coordinate = entry.getKey();
            Path binary = entry.getValue().binary();
            Path pom = resolveOptional(coordinate, "", "pom");
            List<SupportPom> supportPoms = pom == null ? List.of() : resolveSupportPoms(pom);
            Path javadoc = resolveOptional(coordinate, "javadoc", "jar");
            Path sources = resolveOptional(coordinate, "sources", "jar");
            resolved.put(
                    coordinate,
                    new ResolvedArtifact(
                            coordinate,
                            binary,
                            pom,
                            supportPoms,
                            javadoc,
                            sources,
                            entry.getValue().dependencies()));
        }
        return resolved;
    }

    private Map<MavenCoordinate, ResolvedArtifact> resolveBom(MavenCoordinate bom, Path pom)
            throws Exception {
        LinkedHashMap<MavenCoordinate, ResolvedArtifact> resolved = new LinkedHashMap<>();
        resolved.put(
                bom,
                new ResolvedArtifact(bom, null, pom, List.of(), null, null, new ArrayList<>()));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        Map<String, String> properties = new HashMap<>();
        var propertyElement = directChild(project, "properties");
        if (propertyElement != null) {
            var propertyNodes = propertyElement.getChildNodes();
            for (int index = 0; index < propertyNodes.getLength(); index++) {
                var property = propertyNodes.item(index);
                if (property instanceof org.w3c.dom.Element element) {
                    properties.put(element.getLocalName(), element.getTextContent().trim());
                }
            }
        }
        var management = directChild(project, "dependencyManagement");
        var dependencies = management == null ? null : directChild(management, "dependencies");
        if (dependencies == null) return resolved;
        var nodes = dependencies.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (!(nodes.item(index) instanceof org.w3c.dom.Element dependency)
                    || !"dependency".equals(dependency.getLocalName())) continue;
            String version = expand(optionalChildText(dependency, "version"), properties);
            String type = optionalChildText(dependency, "type");
            if (version.isBlank()
                    || "pom".equals(type)
                    || "import".equals(optionalChildText(dependency, "scope"))) continue;
            String group = expand(childText(dependency, "groupId"), properties);
            String artifact = expand(childText(dependency, "artifactId"), properties);
            if (group.contains("${") || artifact.contains("${") || version.contains("${")) continue;
            MavenCoordinate coordinate = new MavenCoordinate(group, artifact, version);
            Map<MavenCoordinate, ResolvedArtifact> artifacts = resolve(coordinate);
            resolved.putAll(artifacts);
            resolved.get(bom).dependencies().add(coordinate);
        }
        return resolved;
    }

    private List<SupportPom> resolveSupportPoms(Path pom) throws Exception {
        LinkedHashMap<MavenCoordinate, SupportPom> supportPoms = new LinkedHashMap<>();
        collectSupportPoms(pom, supportPoms, new HashSet<>(), new HashMap<>());
        return List.copyOf(supportPoms.values());
    }

    private void collectSupportPoms(
            Path pom,
            Map<MavenCoordinate, SupportPom> supportPoms,
            Set<MavenCoordinate> visited,
            Map<String, String> inheritedProperties)
            throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var document = factory.newDocumentBuilder().parse(pom.toFile());
        var project = document.getDocumentElement();
        Map<String, String> properties = inheritedProperties;
        var parent = directChild(project, "parent");
        if (parent != null) {
            MavenCoordinate coordinate =
                    new MavenCoordinate(
                            expand(childText(parent, "groupId"), properties),
                            expand(childText(parent, "artifactId"), properties),
                            expand(childText(parent, "version"), properties));
            if (visited.add(coordinate)) {
                Path parentPom = resolveOptional(coordinate, "", "pom");
                if (parentPom == null) {
                    throw new IllegalStateException(
                            "Could not resolve Maven parent POM " + coordinate.key());
                }
                supportPoms.put(coordinate, new SupportPom(coordinate, parentPom));
                collectSupportPoms(parentPom, supportPoms, visited, properties);
            }
        }

        var propertyElement = directChild(project, "properties");
        if (propertyElement != null) {
            var propertyNodes = propertyElement.getChildNodes();
            for (int index = 0; index < propertyNodes.getLength(); index++) {
                var property = propertyNodes.item(index);
                if (property instanceof org.w3c.dom.Element element) {
                    properties.put(element.getLocalName(), element.getTextContent().trim());
                }
            }
        }

        var dependencyManagement = directChild(project, "dependencyManagement");
        var managedDependencies =
                dependencyManagement == null
                        ? null
                        : directChild(dependencyManagement, "dependencies");
        if (managedDependencies == null) {
            return;
        }
        var dependencies = managedDependencies.getChildNodes();
        for (int index = 0; index < dependencies.getLength(); index++) {
            var dependency = dependencies.item(index);
            if (!(dependency instanceof org.w3c.dom.Element element)
                    || !"dependency".equals(element.getLocalName())
                    || !"import".equals(optionalChildText(element, "scope"))
                    || !"pom".equals(optionalChildText(element, "type"))) {
                continue;
            }
            MavenCoordinate coordinate =
                    new MavenCoordinate(
                            expand(childText(element, "groupId"), properties),
                            expand(childText(element, "artifactId"), properties),
                            expand(childText(element, "version"), properties));
            if (!visited.add(coordinate)) {
                continue;
            }
            Path importedPom = resolveOptional(coordinate, "", "pom");
            if (importedPom == null) {
                throw new IllegalStateException(
                        "Could not resolve imported Maven BOM " + coordinate.key());
            }
            supportPoms.put(coordinate, new SupportPom(coordinate, importedPom));
            collectSupportPoms(importedPom, supportPoms, visited, new HashMap<>(properties));
        }
    }

    private org.w3c.dom.Element directChild(org.w3c.dom.Element parent, String name) {
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            var child = children.item(index);
            if (child instanceof org.w3c.dom.Element element
                    && name.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private String childText(org.w3c.dom.Element parent, String name) {
        var child = directChild(parent, name);
        if (child == null || child.getTextContent().isBlank()) {
            throw new IllegalArgumentException("Maven parent POM is missing " + name);
        }
        return child.getTextContent().trim();
    }

    private String optionalChildText(org.w3c.dom.Element parent, String name) {
        var child = directChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private String expand(String value, Map<String, String> properties) {
        String expanded = value;
        for (int pass = 0; pass < properties.size(); pass++) {
            int start = expanded.indexOf("${");
            if (start < 0) {
                return expanded;
            }
            int end = expanded.indexOf('}', start + 2);
            if (end < 0) {
                return expanded;
            }
            String key = expanded.substring(start + 2, end);
            String replacement = properties.get(key);
            if (replacement == null) {
                return expanded;
            }
            expanded = expanded.substring(0, start) + replacement + expanded.substring(end + 1);
        }
        return expanded;
    }

    private void collect(
            DependencyNode node,
            MavenCoordinate parent,
            LinkedHashMap<MavenCoordinate, ResolvedArtifact> resolved) {
        Artifact artifact = node.getArtifact();
        MavenCoordinate current = null;
        if (artifact != null && "jar".equals(artifact.getExtension())) {
            current =
                    new MavenCoordinate(
                            artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
            resolved.putIfAbsent(
                    current,
                    new ResolvedArtifact(
                            current,
                            artifact.getFile().toPath(),
                            null,
                            null,
                            null,
                            null,
                            new ArrayList<>()));
            if (parent != null) {
                List<MavenCoordinate> dependencies = resolved.get(parent).dependencies();
                if (!dependencies.contains(current)) {
                    dependencies.add(current);
                }
            }
        }
        MavenCoordinate nextParent = current == null ? parent : current;
        for (DependencyNode child : node.getChildren()) {
            collect(child, nextParent, resolved);
        }
    }

    private Path resolveOptional(MavenCoordinate coordinate, String classifier, String extension) {
        Artifact artifact =
                new DefaultArtifact(
                        coordinate.group(),
                        coordinate.artifact(),
                        classifier,
                        extension,
                        coordinate.version());
        try {
            return system.resolveArtifact(
                            session,
                            new ArtifactRequest(artifact, List.of(repository), "wstool-import"))
                    .getArtifact()
                    .getFile()
                    .toPath();
        } catch (ArtifactResolutionException ignored) {
            return null;
        }
    }

    public record ResolvedArtifact(
            MavenCoordinate coordinate,
            Path binary,
            Path pom,
            List<SupportPom> supportPoms,
            Path javadoc,
            Path sources,
            List<MavenCoordinate> dependencies) {}

    public record SupportPom(MavenCoordinate coordinate, Path pom) {}
}
