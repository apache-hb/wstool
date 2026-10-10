package wstool.gradle;

import java.net.URI;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.RepositoryHandler;

/**
 * Replaces project and buildscript repositories with the Maven repository in the wstool sysroot.
 */
public class RepositoriesPlugin implements Plugin<Project> {
    private static final String REPOSITORY_PROPERTY = "wstool.mavenRepository";

    @Override
    public void apply(Project project) {
        Project root = project.getRootProject();
        String marker = REPOSITORY_PROPERTY + ".configured";
        if (root.getExtensions().getExtraProperties().has(marker)) {
            return;
        }
        Object configured = root.findProperty(REPOSITORY_PROPERTY);
        String location =
                configured == null ? System.getenv("WST_MAVEN_REPOSITORY") : configured.toString();
        if (location == null || location.isBlank()) {
            throw new GradleException(
                    "WST_MAVEN_REPOSITORY must point to the Maven repository in the wstool sysroot.");
        }
        URI repository = repositoryUri(location);
        root.getExtensions().getExtraProperties().set(marker, true);
        configureAllProjects(root, repository);
        root.getGradle().projectsEvaluated(gradle -> configureAllProjects(root, repository));
    }

    private void configureAllProjects(Project root, URI repository) {
        root.allprojects(
                subproject -> {
                    configure(subproject.getRepositories(), repository);
                    configure(subproject.getBuildscript().getRepositories(), repository);
                });
    }

    private void configure(RepositoryHandler repositories, URI uri) {
        repositories.clear();
        repositories.maven(
                repository -> {
                    repository.setName("wstool");
                    repository.setUrl(uri);
                    repository.metadataSources(
                            sources -> {
                                sources.mavenPom();
                                sources.gradleMetadata();
                                sources.artifact();
                            });
                });
    }

    private URI repositoryUri(String location) {
        try {
            URI uri = URI.create(location);
            return uri.getScheme() == null ? Path.of(location).toAbsolutePath().toUri() : uri;
        } catch (IllegalArgumentException e) {
            throw new GradleException("Invalid WST_MAVEN_REPOSITORY location: " + location, e);
        }
    }
}
