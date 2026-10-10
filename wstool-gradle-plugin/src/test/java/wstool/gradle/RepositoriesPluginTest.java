package wstool.gradle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoriesPluginTest {
    @TempDir Path tempDir;

    @Test
    void initPluginResolvesGradleDependenciesFromTheLocalMavenRepository() throws Exception {
        Path repository = tempDir.resolve("repository");
        Path project = tempDir.resolve("project");
        Path artifact = repository.resolve("test/local-artifact/1.0");
        Files.createDirectories(artifact);
        Files.createDirectories(project);
        copyResource(
                "repositories-plugin/repository/test/local-artifact/1.0/local-artifact-1.0.pom",
                artifact.resolve("local-artifact-1.0.pom"));
        writeJar(artifact.resolve("local-artifact-1.0.jar"));
        copyResource(
                "repositories-plugin/project/settings.gradle", project.resolve("settings.gradle"));
        copyResource("repositories-plugin/project/build.gradle", project.resolve("build.gradle"));

        Path pluginClasses =
                Path.of(
                        RepositoriesPlugin.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path initScript =
                Path.of("../packages/wstool-gradle/init.gradle").toAbsolutePath().normalize();
        var result =
                GradleRunner.create()
                        .withProjectDir(project.toFile())
                        .withEnvironment(
                                Map.of(
                                        "WST_MAVEN_REPOSITORY",
                                        repository.toAbsolutePath().toString(),
                                        "WST_GRADLE_PLUGIN_JAR",
                                        pluginClasses.toString()))
                        .withArguments(
                                "--init-script",
                                initScript.toString(),
                                "verifyLocalArtifact",
                                "--offline")
                        .build();

        assertTrue(result.getOutput().contains("WSTOOL_LOCAL_ARTIFACT=local-artifact-1.0.jar"));
    }

    private void writeJar(Path path) throws IOException {
        Path sample = resourcePath("repositories-plugin/repository/sample.txt");
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("sample.txt"));
            try (InputStream input = Files.newInputStream(sample)) {
                input.transferTo(output);
            }
            output.closeEntry();
        }
    }

    private void copyResource(String name, Path destination) throws IOException {
        Files.copy(resourcePath(name), destination);
    }

    private Path resourcePath(String name) throws IOException {
        try {
            var resource = getClass().getClassLoader().getResource(name);
            if (resource == null) {
                throw new IOException("Test resource not found: " + name);
            }
            return Path.of(resource.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid test resource URI: " + name, e);
        }
    }
}
