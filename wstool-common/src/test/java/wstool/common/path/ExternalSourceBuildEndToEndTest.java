package wstool.common.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageConfig;
import wstool.common.packages.PackageFactory;
import wstool.common.pkl.PklHelper;
import wstool.common.workspace.LocalWorkspace;
import wstool.test.components.LocalFileHttpServer;
import wstool.test.components.TestResources;

@Tag("integration")
class ExternalSourceBuildEndToEndTest {
    private static final PackageMajorVersion PACKAGE =
            new PackageMajorVersion("external-source-e2e", "1.0");
    @TempDir Path temporaryDirectory;

    @Test
    void downloadsParsesOverlaysPatchesAndBuildsExternalPackageSources() throws Exception {
        Path fixtureDirectory =
                TestResources.copyDirectory(
                        getClass(),
                        "/external-source-fixtures",
                        temporaryDirectory.resolve("fixtures"));
        Path packageDirectory = fixtureDirectory.resolve("e2e-package");
        Map<String, Path> servedFiles =
                Map.of(
                        "/project.tar.gz", fixtureDirectory.resolve("e2e-upstream/project.tar.gz"),
                        "/version.txt", fixtureDirectory.resolve("e2e-files/version.txt"));
        try (LocalFileHttpServer server = new LocalFileHttpServer(servedFiles)) {
            URI baseUri = server.baseUri();
            PackageConfig packageConfig;
            try (PklHelper pklHelper =
                    new PklHelper(Map.of("WSTOOL_TEST_HTTP_BASE", baseUri.toString()))) {
                packageConfig = new PackageFactory(pklHelper).open(packageDirectory);
            }
            assertEquals(PACKAGE, packageConfig.name());
            assertEquals(2, packageConfig.externalSources().size());

            Path workspacePath = temporaryDirectory.resolve("workspace");
            Files.createDirectories(workspacePath);
            LocalWorkspace workspace = new LocalWorkspace(workspacePath, null, null, null);
            workspace.storage().setup();
            workspace.storage().refresh(List.of(PACKAGE), List.of());
            copyTree(packageDirectory, workspace.source(PACKAGE));

            HttpClient httpClient = HttpClient.newHttpClient();
            BuildRunner buildRunner =
                    new BuildRunner(
                            new PathBuilder(),
                            new BuildSourcePreparer(
                                    httpClient,
                                    new ArchiveCache(httpClient),
                                    new ArchiveExtractor(new ArchiveStreamFactory())));
            assertEquals(
                    0,
                    buildRunner.runBuild(workspace, packageConfig, List.of("wstool/bin/build.sh")));

            Path source = workspace.source(PACKAGE);
            Path intermediate = workspace.buildSource(PACKAGE);
            Path prefix = workspace.prefix(PACKAGE);
            assertEquals(
                    "Hello from the patched third-party source archive.\n"
                            + "Overlay from the workspace package.\n"
                            + "Release version 2.7.3\n",
                    Files.readString(prefix.resolve("package-output.txt")));
            assertEquals(
                    intermediate.toString(),
                    Files.readString(prefix.resolve("build-directory.txt")).trim());
            assertEquals(
                    "Hello from the patched third-party source archive.\n",
                    Files.readString(intermediate.resolve("src/message.txt")));
            assertEquals(
                    "Overlay from the workspace package.\n",
                    Files.readString(source.resolve("src/package-overlay.txt")));
            assertFalse(Files.exists(source.resolve("src/message.txt")));
            assertFalse(Files.exists(source.resolve("data/version.txt")));
            assertTrue(Files.isRegularFile(intermediate.resolve("README.txt")));
            assertEquals(1, server.requestCount("/project.tar.gz"));
            assertEquals(1, server.requestCount("/version.txt"));
            assertEquals(2, countFiles(workspace.cache().resolve("sources/downloads")));
        }
    }

    private static long countFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
        }
    }
}
