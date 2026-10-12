package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import software.amazon.awssdk.services.s3.S3Client;
import wstool.test.components.StorageServerManager;

/** Builds a real Gradle package using only wstool CLI workspace and build commands. */
@Testcontainers(disabledWithoutDocker = true)
class GradleBuildEndToEndTest {
    private static final String BUCKET = "wstool-gradle-e2e";
    private static final String BUILD_IMAGE = "wstool-gradle-e2e:latest";

    @Container
    private static final GenericContainer<?> GARAGE = StorageServerManager.container();
    private static final StorageServerManager STORAGE = new StorageServerManager(GARAGE);

    @AutoClose
    private static S3Client s3;

    @TempDir Path tempDir;
    private Path artifactRepository;
    private Path projectRepository;

    @BeforeAll
    static void initializeStorageServer() throws Exception {
        STORAGE.setup();
        s3 = STORAGE.createBucket(BUCKET);
    }

    @Test
    void cliBuildUsesWstoolMavenRepositoryForGradleDependencies() throws Exception {
        Path imageDockerfile = resourcePath("gradle-e2e/Dockerfile");
        new ImageFromDockerfile(BUILD_IMAGE, false)
                .withFileFromPath("Dockerfile", imageDockerfile)
                .get();

        Path distributionRepository = createDistributionRepository();
        Path workspaces = tempDir.resolve("workspaces");
        Files.createDirectories(workspaces);
        runCli(workspaces, "workspace", "create", "--name", "gradle-project");
        Path workspace = workspaces.resolve("gradle-project");
        runCli(
                workspace,
                "workspace",
                "setup",
                "--distro",
                "gradle-e2e",
                "--from",
                distributionRepository.toString(),
                "--branch",
                "master");
        runCli(
                workspace,
                "workspace",
                "add",
                "--package",
                "local-artifact",
                "--package",
                "gradle-smoke");

        runCli(workspace, "build", "local-artifact@1.0.0");
        runCli(workspace, "build", "gradle-smoke@1.0.0");

        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/prefix/gradle-smoke/gradle-build/classes/java/main/smoke/App.class")),
                "Gradle did not compile the project using the Maven artifact from wstool");

        runCli(workspace, "workspace", "remove", "-p", "local-artifact");
        assertFalse(Files.exists(workspace.resolve("src/local-artifact")));
        assertFalse(Files.exists(workspace.resolve("private/prefix/local-artifact")));

        runCli(workspace, "build", "gradle-smoke@1.0.0");
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/prefix/local-artifact/share/maven-repo/test/local-artifact/1.0.0/local-artifact-1.0.0.jar")),
                "Build did not restore the removed package from its prebuilt artifact");
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/prefix/gradle-smoke/gradle-build/classes/java/main/smoke/App.class")),
                "Gradle did not rebuild using the restored prebuilt artifact");
    }

    private Path createDistributionRepository() throws Exception {
        Path packageRepositories = tempDir.resolve("package-repositories");
        artifactRepository = packageRepositories.resolve("local-artifact");
        projectRepository = packageRepositories.resolve("gradle-smoke");
        Path artifactPackage = artifactRepository;
        Path projectPackage = projectRepository;
        copyTree(resourcePath("gradle-e2e/packages/local-artifact"), artifactPackage);
        copyTree(resourcePath("gradle-e2e/packages/gradle-smoke"), projectPackage);
        writeMavenArtifact(artifactPackage);

        initializeGitRepository(artifactPackage);
        initializeGitRepository(projectPackage);
        Path localRepository = tempDir.resolve("distribution");
        Files.createDirectories(localRepository);
        Files.copy(
                resourcePath("gradle-e2e/distribution/distribution.pkl"),
                localRepository.resolve("distribution.pkl"));
        initializeGitRepository(localRepository);
        return localRepository;
    }

    private void writeMavenArtifact(Path artifactPackage) throws Exception {
        Path classes = tempDir.resolve("artifact-classes");
        Path source = resourcePath("gradle-e2e/maven-artifact/fixture/ArtifactMarker.java");
        Files.createDirectories(classes);
        int compileStatus =
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "--release",
                                "17",
                                "-d",
                                classes.toString(),
                                source.toString());
        assertEquals(0, compileStatus, "Could not compile the Maven fixture artifact");

        Path jar =
                artifactPackage.resolve(
                        "repository/test/local-artifact/1.0.0/local-artifact-1.0.0.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/ArtifactMarker.class"));
            Files.copy(classes.resolve("fixture/ArtifactMarker.class"), output);
            output.closeEntry();
        }
    }

    private void initializeGitRepository(Path repository) throws Exception {
        run(repository, "git", "init", "--initial-branch=master");
        run(repository, "git", "config", "user.name", "wstool test");
        run(repository, "git", "config", "user.email", "wstool-test@example.invalid");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "--message", "Gradle CLI test distribution");
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void runCli(Path directory, String... arguments) throws Exception {
        List<String> command =
                new java.util.ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                Main.class.getName()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        var credentials =
                s3.serviceClientConfiguration().credentialsProvider().resolveIdentity().join();
        builder.environment().put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
        builder.environment().put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
        builder.environment().put("AWS_REGION", "us-east-1");
        builder.environment()
                .put(
                        "AWS_S3_ENDPOINT",
                        STORAGE.endpoint().toString());
        builder.environment().put("WST_BUILD_IMAGE", BUILD_IMAGE);
        builder.environment().put("WSTOOL_CLI_ARCHIVE", requiredEnv("WSTOOL_CLI_ARCHIVE"));
        builder.environment().put("WSTOOL_GRADLE_PACKAGE_URI", packageUri("WSTOOL_GRADLE_PACKAGE"));
        builder.environment()
                .put("WSTOOL_GRADLE_PACKAGE_CHECKSUM", packageChecksum("WSTOOL_GRADLE_PACKAGE"));
        builder.environment()
                .put(
                        "WSTOOL_GRADLE_RUNNER_PACKAGE_URI",
                        packageUri("WSTOOL_GRADLE_RUNNER_PACKAGE"));
        builder.environment()
                .put(
                        "WSTOOL_GRADLE_RUNNER_PACKAGE_CHECKSUM",
                        packageChecksum("WSTOOL_GRADLE_RUNNER_PACKAGE"));
        builder.environment()
                .put("WSTOOL_MAVEN_TOOL_PACKAGE_URI", packageUri("WSTOOL_MAVEN_TOOL_PACKAGE"));
        builder.environment()
                .put(
                        "WSTOOL_MAVEN_TOOL_PACKAGE_CHECKSUM",
                        packageChecksum("WSTOOL_MAVEN_TOOL_PACKAGE"));
        builder.environment()
                .put("WSTOOL_GRADLE_E2E_ARTIFACT_REPO", artifactRepository.toUri().toString());
        builder.environment()
                .put("WSTOOL_GRADLE_E2E_PROJECT_REPO", projectRepository.toUri().toString());
        builder.environment()
                .put(
                        "WSTOOL_CLI_CHECKSUM",
                        sha256(Path.of(URI.create(requiredEnv("WSTOOL_CLI_ARCHIVE")))));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(
                0, process.waitFor(), "CLI failed: " + String.join(" ", arguments) + "\n" + output);
    }

    private void run(Path directory, String... command) throws Exception {
        Process process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + " failed:\n" + output);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test package path in " + name);
        }
        return value;
    }

    private String packageUri(String environment) {
        return archivePath(environment).toUri().toString();
    }

    private String packageChecksum(String environment) throws Exception {
        return sha256(archivePath(environment));
    }

    private Path archivePath(String environment) {
        String value = requiredEnv(environment);
        return value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private static Path resourcePath(String name) throws Exception {
        var resource = GradleBuildEndToEndTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }

}
