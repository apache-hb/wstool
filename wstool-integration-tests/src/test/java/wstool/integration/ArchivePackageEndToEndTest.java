package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.cli.Main;
import wstool.test.components.CliProcess;
import wstool.test.components.CliResult;
import wstool.test.components.LocalFileHttpServer;
import wstool.test.components.PackageGitManager;
import wstool.test.components.TestResources;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class ArchivePackageEndToEndTest {
    private static final Path OUTPUT_DIRECTORY =
            Path.of(System.getProperty("wstool.integration-tests.output-directory"))
                    .resolve("archive-package");
    private static final CliProcess WSTOOL =
            new CliProcess(Main.class, "wstool.cli.classpath", List.of());

    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    @Test
    void downloadsExtractsLinksAndBuildsArchivePackage() throws Exception {
        deleteOutputDirectory();
        Files.createDirectories(OUTPUT_DIRECTORY);
        PackageGitManager git = new PackageGitManager(OUTPUT_DIRECTORY, GITEA);

        Path archive = OUTPUT_DIRECTORY.resolve("archive-package.tar.gz");
        try (InputStream resource = getClass().getResourceAsStream("/archive-package.tar.gz")) {
            if (resource == null) {
                throw new IllegalStateException("Missing archive package fixture");
            }
            Files.copy(resource, archive);
        }

        Path distribution =
                TestResources.copyDirectory(
                        getClass(),
                        "/archive-distribution",
                        OUTPUT_DIRECTORY.resolve("distribution"));
        URI distributionUri =
                git.create("archive-distribution-" + UUID.randomUUID(), distribution, Map.of());

        try (LocalFileHttpServer server =
                new LocalFileHttpServer(Map.of("/archive-package.tar.gz", archive))) {
            URI archiveUri = server.baseUri().resolve("/archive-package.tar.gz");
            Map<String, String> environment = Map.of("WSTOOL_ARCHIVE_URI", archiveUri.toString());
            CliResult workspaceResult =
                    WSTOOL.run(
                            OUTPUT_DIRECTORY,
                            OUTPUT_DIRECTORY,
                            environment,
                            "workspace",
                            "create",
                            "--name",
                            "archive-workspace",
                            "--distribution",
                            distributionUri.toString());
            assertSuccess(workspaceResult);

            Path workspace = OUTPUT_DIRECTORY.resolve("archive-workspace");
            CliResult addResult =
                    WSTOOL.run(
                            OUTPUT_DIRECTORY,
                            workspace,
                            environment,
                            "workspace",
                            "add",
                            "--package",
                            "archive-package");
            assertSuccess(addResult);

            Path source = workspace.resolve("src/archive-package");
            Path privateSource = workspace.resolve("private/source/archive-package/1.0.0");
            assertTrue(Files.isSymbolicLink(source));
            assertEquals(privateSource.toRealPath(), source.toRealPath());
            assertTrue(Files.isRegularFile(privateSource.resolve("wstool.pkl")));

            CliResult buildResult =
                    WSTOOL.run(OUTPUT_DIRECTORY, source, environment, "package", "run");
            assertSuccess(buildResult);
            assertEquals(
                    "built from archive\n",
                    Files.readString(
                            workspace.resolve(
                                    "private/prefix/archive-package/1.0.0/archive-output.txt")));
            assertEquals(
                    source.toAbsolutePath().normalize().toString(),
                    Files.readString(
                                    workspace.resolve(
                                            "private/prefix/archive-package/1.0.0/archive-source-path.txt"))
                            .strip());
            assertEquals(1, server.requestCount("/archive-package.tar.gz"));
        }
    }

    private void deleteOutputDirectory() throws Exception {
        if (!Files.exists(OUTPUT_DIRECTORY)) {
            return;
        }
        try (var paths = Files.walk(OUTPUT_DIRECTORY)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.delete(path);
            }
        }
    }

    private void assertSuccess(CliResult result) {
        assertEquals(0, result.exitCode(), result.output() + result.error());
    }
}
