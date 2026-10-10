package wstool.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MavenDependencyResolverTest {
    @TempDir Path tempDir;

    private static final MavenCoordinate ROOT =
            new MavenCoordinate("org.hamcrest", "hamcrest-library", "1.3");

    @Test
    void importsSmithyBomAsMajorVersionPackagesForInspection() throws Exception {
        MavenCoordinate bom = new MavenCoordinate("software.amazon.smithy", "smithy-bom", "1.74.0");
        Path output = Path.of("build/recursive-import-e2e/maven-smithy-bom").toAbsolutePath();
        deleteTree(output);
        Path localRepository =
                Path.of("build/recursive-import-e2e-cache/maven-smithy-local").toAbsolutePath();
        try (var proxy =
                new CachedMavenProxy(
                        Path.of("build/recursive-import-e2e-cache/maven-proxy").toAbsolutePath())) {
            assertEquals(0, importMaven(output, localRepository, proxy.url(), bom.key()));
            String entries = Files.readString(output.resolve("packages.pkl"));
            assertTrue(entries.contains("name = \"software_amazon_smithy_smithy-bom\""));
            assertTrue(entries.contains("version = \"1.x\""));
            try (var paths = Files.walk(output.resolve("package-tree/packages"))) {
                assertTrue(paths.filter(Files::isRegularFile).count() > 100);
            }
            System.out.println("Inspectable Smithy BOM import output: " + output);
        }
    }

    @Test
    void recursiveCliImportsShareOneInspectableOutputTree() throws Exception {
        Path output = Path.of("build/recursive-import-e2e/maven").toAbsolutePath();
        deleteTree(output);
        Path proxyCache = Path.of("build/recursive-import-e2e-cache/maven-proxy").toAbsolutePath();
        Path localRepository =
                Path.of("build/recursive-import-e2e-cache/maven-local").toAbsolutePath();
        try (var proxy = new CachedMavenProxy(proxyCache)) {
            assertEquals(0, importMaven(output, localRepository, proxy.url(), ROOT.key()));
            MavenCoordinate core = new MavenCoordinate("org.hamcrest", "hamcrest-core", "1.3");
            assertEquals(0, importMaven(output, localRepository, proxy.url(), core.key()));

            Path tree = output.resolve("package-tree/packages");
            assertTrue(
                    Files.isRegularFile(
                            tree.resolve(
                                    "org_hamcrest_hamcrest-library/1.x/repository/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.jar")));
            Path coreJar =
                    tree.resolve(
                            "org_hamcrest_hamcrest-core/1.x/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar");
            assertTrue(Files.isRegularFile(coreJar));
            try (var packageDirectories = Files.list(tree)) {
                assertEquals(2, packageDirectories.count());
            }
            try (ZipFile zip = new ZipFile(output.resolve("wstool-maven-import.zip").toFile())) {
                assertEquals(
                        1,
                        zip.stream()
                                .filter(entry -> entry.getName().endsWith("hamcrest-core-1.3.jar"))
                                .count());
                assertTrue(
                        zip.getEntry("packages/org_hamcrest_hamcrest-core/1.x/wstool.pkl") != null);
            }
            String entries = Files.readString(output.resolve("packages.pkl"));
            assertEquals(2, entries.split("new wstoolDistribution.SourceCode", -1).length - 1);
            assertTrue(Files.isRegularFile(output.resolve("maven-import-roots.json")));
            System.out.println("Inspectable Maven recursive import output: " + output);
        }
    }

    @Test
    void resolvesRealTransitiveMavenArtifactsAndCachesRepositoryResponses() throws Exception {
        Path cache = Path.of("build/test-maven-central-cache").toAbsolutePath();
        try (var proxy = new CachedMavenProxy(cache)) {
            Path localRepository = tempDir.resolve("resolver-cache");
            var resolved = new MavenDependencyResolver(proxy.url(), localRepository).resolve(ROOT);

            assertTrue(resolved.containsKey(ROOT));
            assertTrue(
                    resolved.keySet().stream()
                            .anyMatch(coordinate -> coordinate.artifact().equals("hamcrest-core")),
                    resolved.keySet().toString());
            assertTrue(resolved.get(ROOT).pom() != null);

            Path output = tempDir.resolve("generated");
            Path bundle = new PackageBundleWriter().write(resolved, output, null, "ubuntu:24.04");
            assertTrue(Files.isRegularFile(bundle));
            String pkl = Files.readString(output.resolve("packages.pkl"));
            assertTrue(pkl.contains("name = \"org_hamcrest_hamcrest-library\""));
            assertFalse(pkl.contains("https://"));
            try (ZipFile zip = new ZipFile(bundle.toFile())) {
                var packageConfig =
                        zip.getInputStream(
                                zip.getEntry(
                                        "packages/org_hamcrest_hamcrest-library/1.x/wstool.pkl"));
                assertTrue(packageConfig != null);
                String generatedPackageConfig =
                        new String(
                                packageConfig.readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(
                        generatedPackageConfig.contains(
                                "package://pkl.konna.cloud/pkl/wstool@1.0.0#/package.pkl"));
                assertTrue(generatedPackageConfig.contains("tool = \"maven-wstool-import\""));
                assertTrue(
                        generatedPackageConfig.contains(
                                "buildDependencies { [\"maven-wstool-import\"] = \"1.0.0\" }"));
                assertTrue(
                        zip.getEntry(
                                        "packages/org_hamcrest_hamcrest-library/1.x/wstool/bin/maven-wstool-import")
                                == null);
                assertTrue(
                        zip.getEntry(
                                        "packages/org_hamcrest_hamcrest-library/1.x/repository/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.jar")
                                != null);
            }

            int requestsAfterInitialResolve = proxy.upstreamRequests();
            Path secondLocalRepository = tempDir.resolve("resolver-cache-second");
            var secondResolve =
                    new MavenDependencyResolver(proxy.url(), secondLocalRepository).resolve(ROOT);
            assertEquals(resolved.keySet(), secondResolve.keySet());
            assertEquals(
                    requestsAfterInitialResolve,
                    proxy.upstreamRequests(),
                    "A repeat resolution should be served entirely from the persistent proxy cache");

            Path cliOutput = tempDir.resolve("cli-output");
            int status =
                    new CommandLine(Main.class)
                            .execute(
                                    "maven",
                                    "--output",
                                    cliOutput.toString(),
                                    "--repository",
                                    proxy.url(),
                                    "--local-repository",
                                    tempDir.resolve("cli-local-cache").toString(),
                                    ROOT.key());
            assertEquals(0, status);
            assertTrue(Files.isRegularFile(cliOutput.resolve("packages.pkl")));
            assertEquals(requestsAfterInitialResolve, proxy.upstreamRequests());

            MavenCoordinate core = new MavenCoordinate("org.hamcrest", "hamcrest-core", "1.3");
            int secondStatus =
                    new CommandLine(Main.class)
                            .execute(
                                    "maven",
                                    "--output",
                                    cliOutput.toString(),
                                    "--repository",
                                    proxy.url(),
                                    "--local-repository",
                                    tempDir.resolve("cli-local-cache-second").toString(),
                                    core.key());
            assertEquals(0, secondStatus);
            String combinedEntries = Files.readString(cliOutput.resolve("packages.pkl"));
            assertEquals(
                    2, combinedEntries.split("new wstoolDistribution.SourceCode", -1).length - 1);
            assertEquals(
                    2,
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(cliOutput.resolve("maven-import-roots.json").toFile())
                            .size());
            assertEquals(requestsAfterInitialResolve, proxy.upstreamRequests());
        }
    }

    private int importMaven(
            Path output, Path localRepository, String repository, String coordinate) {
        return new CommandLine(Main.class)
                .execute(
                        "maven",
                        "--output",
                        output.toString(),
                        "--repository",
                        repository,
                        "--local-repository",
                        localRepository.toString(),
                        coordinate);
    }

    private void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
