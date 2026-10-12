package wstool.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pkl.config.java.JavaType;
import picocli.CommandLine;
import wstool.Distribution.Archive;
import wstool.Distribution.Folder;
import wstool.Distribution.PackageSource;
import wstool.Package;
import wstool.simple.PklHelper;

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
            List<PackageSource> entries = readPackageSources(output.resolve("packages.pkl"));
            PackageSource smithyBom = findPackage(entries, "software_amazon_smithy_smithy-bom");
            assertEquals("1.x", smithyBom.getVersion());
            try (var paths = Files.walk(output.resolve("package-tree/packages"))) {
                assertTrue(paths.filter(Files::isRegularFile).count() > 10);
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
            Path coreConfig = tree.resolve("org_hamcrest_hamcrest-core/1.x/wstool.pkl");
            assertTrue(Files.isRegularFile(coreConfig));
            assertInstanceOf(
                    Package.ExternalFiles.class,
                    readPackage(coreConfig).getImportOptions().getSource());
            assertTrue(
                    Files.notExists(
                            tree.resolve(
                                    "org_hamcrest_hamcrest-core/1.x/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar")));
            try (var packageDirectories = Files.list(tree)) {
                assertEquals(2, packageDirectories.count());
            }
            try (ZipFile zip = new ZipFile(output.resolve("wstool-maven-import.zip").toFile())) {
                assertEquals(
                        0,
                        zip.stream()
                                .filter(entry -> entry.getName().endsWith("hamcrest-core-1.3.jar"))
                                .count());
                assertTrue(
                        zip.getEntry("packages/org_hamcrest_hamcrest-core/1.x/wstool.pkl") != null);
            }
            List<PackageSource> entries = readPackageSources(output.resolve("packages.pkl"));
            assertEquals(2, entries.size());
            assertEquals("1.x", findPackage(entries, "org_hamcrest_hamcrest-library").getVersion());
            assertEquals("1.x", findPackage(entries, "org_hamcrest_hamcrest-core").getVersion());
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
            Path bundle = new PackageBundleWriter().write(resolved, output, proxy.url(), null);
            assertTrue(Files.isRegularFile(bundle));
            List<PackageSource> entries = readPackageSources(output.resolve("packages.pkl"));
            PackageSource library = findPackage(entries, "org_hamcrest_hamcrest-library");
            assertEquals("1.x", library.getVersion());
            for (PackageSource source : entries) {
                Folder folder = assertInstanceOf(Folder.class, source);
                Archive archive = assertInstanceOf(Archive.class, folder.getFrom());
                assertEquals("file", archive.getUri().getScheme());
            }
            try (ZipFile zip = new ZipFile(bundle.toFile())) {
                Path packageConfigPath = tempDir.resolve("maven-package.pkl");
                try (var packageConfig =
                        zip.getInputStream(
                                zip.getEntry(
                                        "packages/org_hamcrest_hamcrest-library/1.x/wstool.pkl"))) {
                    assertTrue(packageConfig != null);
                    Files.copy(packageConfig, packageConfigPath);
                }
                Package packageConfig = readPackage(packageConfigPath);
                assertEquals("maven-wstool-import", packageConfig.getBuildOptions().getTool());
                assertEquals(
                        Map.of("maven-wstool-import", "1.0.0"),
                        packageConfig.getBuildDependencies());
                assertTrue(
                        zip.getEntry(
                                        "packages/org_hamcrest_hamcrest-library/1.x/wstool/bin/maven-wstool-import")
                                == null);
                assertTrue(
                        zip.getEntry(
                                        "packages/org_hamcrest_hamcrest-library/1.x/repository/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.jar")
                                == null);
                assertTrue(
                        packageConfig.getImportOptions().getSource()
                                instanceof Package.ExternalFiles);
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
            List<PackageSource> combinedEntries =
                    readPackageSources(cliOutput.resolve("packages.pkl"));
            assertEquals(2, combinedEntries.size());
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

    private List<PackageSource> readPackageSources(Path path) throws Exception {
        try (var pkl = new PklHelper(Map.of())) {
            return pkl.evaluate(path).get("packages").as(JavaType.listOf(PackageSource.class));
        }
    }

    private Package readPackage(Path path) throws Exception {
        try (var pkl = new PklHelper(Map.of())) {
            return pkl.evaluate(path).as(Package.class);
        }
    }

    private PackageSource findPackage(List<PackageSource> packages, String name) {
        return packages.stream()
                .filter(pkg -> pkg.getName().equals(name))
                .findFirst()
                .orElseThrow();
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
