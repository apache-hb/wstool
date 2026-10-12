package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import wstool.test.components.PackageGitManager;

@Testcontainers(disabledWithoutDocker = true)
class WorkspaceAddRemoveEndToEndTest {
    @Container
    private static final GiteaContainer gitea = PackageGitManager.container();

    @TempDir Path tempDir;
    private PackageGitManager gitManager;
    private URI packageRepository;

    @BeforeEach
    void createGitManager() {
        gitManager = new PackageGitManager(tempDir, gitea);
    }

    @Test
    void cliAddsPackageFromLocallyUpdatedDistributionAndRemovesIt() throws Exception {
        Path workspace = createWorkspaceWithLocallyUpdatedDistribution("updated");

        runCli(workspace, "workspace", "add", "--package", "sample");

        Path packageSource = workspace.resolve("src/sample");
        assertTrue(Files.isRegularFile(packageSource.resolve("wstool.pkl")));
        assertEquals(
                "workspace add remove fixture\n",
                Files.readString(packageSource.resolve("README.md")));
        assertPackageCount(workspace.resolve("wstool.db"), 1);
        assertPackageRow(workspace.resolve("wstool.db"), "sample", "1.0.0", "1");

        runCli(workspace, "workspace", "remove", "-p", "sample");

        assertFalse(Files.exists(packageSource));
        assertFalse(Files.exists(workspace.resolve("private/prefix/sample")));
        assertFalse(Files.exists(workspace.resolve("private/build/sample")));
        assertPackageCount(workspace.resolve("wstool.db"), 0);
    }

    @Test
    void cliRefreshesPackageTableFromLocallyChangedDistribution() throws Exception {
        Path workspace = createWorkspaceWithLocallyUpdatedDistribution("with-catalog-entry");

        runCli(workspace, "workspace", "add", "--package", "sample");

        Path database = workspace.resolve("wstool.db");
        assertPackageCount(database, 2);
        assertPackageRow(database, "sample", "1.0.0", "1");
        assertPackageRow(database, "catalog-only", "2.0.0", "0");
    }

    private Path createWorkspaceWithLocallyUpdatedDistribution(String updatedConfig)
            throws Exception {
        Path packageRepository = tempDir.resolve("sample-repository");
        copyTree(resourcePath("workspace-add-remove/packages/sample"), packageRepository);
        this.packageRepository = uploadRepository("sample-package", packageRepository);

        Path distributionRepository = tempDir.resolve("distribution-repository");
        copyTree(resourcePath("workspace-add-remove/distribution/initial"), distributionRepository);
        URI distributionUri = uploadRepository("distribution", distributionRepository);

        Path workspaces = tempDir.resolve("workspaces");
        Files.createDirectories(workspaces);
        runCli(workspaces, "workspace", "create", "--name", "demo");

        Path workspace = workspaces.resolve("demo");
        runCli(
                workspace,
                "workspace",
                "setup",
                "--distro",
                "workspace-add-remove-test",
                "--from",
                distributionUri.toString(),
                "--branch",
                "master");

        Files.copy(
                resourcePath(
                        "workspace-add-remove/distribution/" + updatedConfig + "/distribution.pkl"),
                workspace.resolve("distribution/distribution.pkl"),
                StandardCopyOption.REPLACE_EXISTING);
        return workspace;
    }

    private void assertPackageCount(Path database, int expectedRows) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM packages")) {
            assertTrue(result.next());
            assertEquals(expectedRows, result.getInt(1));
        }
    }

    private void assertPackageRow(Path database, String name, String version, String cloned)
            throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT cloned FROM packages WHERE name = ? AND version = ?")) {
            statement.setString(1, name);
            statement.setString(2, version);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "Package missing from workspace database: " + name);
                assertEquals(cloned, result.getString("cloned"));
                assertFalse(result.next(), "Duplicate package rows for " + name);
            }
        }
    }

    private URI uploadRepository(String name, Path directory) throws Exception {
        String repositoryName = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI repository = gitManager.create(repositoryName);
        gitManager.uploadFiles(repositoryName, directory);
        return repository;
    }

    private void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private Path resourcePath(String resourceName) throws Exception {
        URL resource = getClass().getClassLoader().getResource(resourceName);
        if (resource == null) {
            throw new IOException("Test resource not found: " + resourceName);
        }
        return Path.of(resource.toURI());
    }

    private void runCli(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        processBuilder
                .environment()
                .put("PACKAGE_REPOSITORY", packageRepository.toString());
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "CLI failed: " + String.join(" ", arguments) + "\n" + output);
    }

}
