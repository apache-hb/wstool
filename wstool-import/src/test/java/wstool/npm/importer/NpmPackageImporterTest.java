package wstool.npm.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import wstool.importer.Main;

class NpmPackageImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsAwsMdaaSftpServerAsMajorVersionPackagesForInspection() throws Exception {
        Path output =
                Path.of("build/recursive-import-e2e/npm-aws-mdaa-sftp-server").toAbsolutePath();
        deleteTree(output);
        Path proxyCache = Path.of("build/recursive-import-e2e-cache/npm-proxy").toAbsolutePath();
        Path npmCache = Path.of("build/recursive-import-e2e-cache/npm-cache").toAbsolutePath();
        try (var proxy = new CachedNpmProxy(proxyCache)) {
            assertEquals(0, importPackage(output, npmCache, proxy.url(), "@aws-mdaa/sftp-server"));
            String entries = Files.readString(output.resolve("packages.pkl"));
            assertTrue(entries.contains("name = \"npm_@aws-mdaa/sftp-server\""));
            assertTrue(
                    Files.isRegularFile(
                            output.resolve(
                                    "package-tree/packages/@aws-mdaa/sftp-server/1.x/wstool.pkl")));
            assertTrue(entries.contains("version = \""));
            assertTrue(entries.contains(".x\""));
            try (var paths = Files.walk(output.resolve("package-tree/packages"))) {
                assertTrue(paths.filter(Files::isRegularFile).count() > 100);
            }
            System.out.println("Inspectable AWS MDAA npm import output: " + output);
        }
    }

    @Test
    void recursiveCliImportsShareOneInspectableOutputTree() throws Exception {
        Path output = Path.of("build/recursive-import-e2e/npm").toAbsolutePath();
        deleteTree(output);
        Path proxyCache = Path.of("build/recursive-import-e2e-cache/npm-proxy").toAbsolutePath();
        Path npmCache = Path.of("build/recursive-import-e2e-cache/npm-cache").toAbsolutePath();
        try (var proxy = new CachedNpmProxy(proxyCache)) {
            assertEquals(0, importPackage(output, npmCache, proxy.url(), "debug@4.3.4"));
            assertEquals(0, importPackage(output, npmCache, proxy.url(), "ms@2.1.2"));

            Path packages = output.resolve("package-tree/packages");
            assertTrue(Files.isRegularFile(packages.resolve("debug/4.x/package/package.json")));
            assertTrue(Files.isRegularFile(packages.resolve("ms/2.x/package/package.json")));
            try (var packageDirectories = Files.list(packages)) {
                assertEquals(2, packageDirectories.count());
            }
            try (ZipFile zip = new ZipFile(output.resolve("wstool-npm-import.zip").toFile())) {
                assertEquals(
                        1,
                        zip.stream()
                                .filter(
                                        entry ->
                                                entry.getName()
                                                        .equals(
                                                                "packages/ms/2.x/package/package.json"))
                                .count());
                assertTrue(zip.getEntry("packages/debug/4.x/wstool.pkl") != null);
                assertTrue(zip.getEntry("packages/ms/2.x/wstool.pkl") != null);
                assertTrue(zip.getEntry("packages/debug/4.x/wstool/bin/npm-wstool") == null);
            }
            String entries = Files.readString(output.resolve("packages.pkl"));
            assertEquals(2, entries.split("new wstoolDistribution.SourceCode", -1).length - 1);
            assertTrue(Files.isRegularFile(output.resolve("npm-import-roots.json")));
            System.out.println("Inspectable npm recursive import output: " + output);
        }
    }

    @Test
    void importsRealRegistryDependenciesAndUsesPersistentProxyCache() throws Exception {
        Path proxyCache = Path.of("build/test-npm-registry-cache").toAbsolutePath();
        try (var proxy = new CachedNpmProxy(proxyCache)) {
            Path output = tempDir.resolve("first");
            int status = importPackage(output, tempDir.resolve("npm-cache-first"), proxy.url());
            assertEquals(0, status);

            String packageEntries = Files.readString(output.resolve("packages.pkl"));
            assertTrue(packageEntries.contains("module wstool.importedNpmPackages"));
            assertTrue(packageEntries.contains("name = \"npm_debug\""));
            assertTrue(packageEntries.contains("name = \"npm_ms\""));
            String directDependencies = Files.readString(output.resolve("npm-dependencies.pkl"));
            assertTrue(directDependencies.contains("[\"npm_debug\"] = \"4.x\""));
            assertFalse(directDependencies.contains("npm_ms"));

            Path debugPackage =
                    output.resolve("package-tree/packages/debug/4.x/package/package.json");
            assertTrue(Files.isRegularFile(debugPackage));
            String debugConfig =
                    Files.readString(output.resolve("package-tree/packages/debug/4.x/wstool.pkl"));
            assertTrue(debugConfig.contains("[\"wstool-npm-import\"] = \"1.0.0\""));
            try (ZipFile zip = new ZipFile(output.resolve("wstool-npm-import.zip").toFile())) {
                assertTrue(zip.getEntry("packages/debug/4.x/package/package.json") != null);
                assertTrue(zip.getEntry("packages/ms/2.x/package/package.json") != null);
            }

            int upstreamRequests = proxy.upstreamRequests();
            try (var cachedResponses = Files.list(proxyCache)) {
                assertTrue(
                        cachedResponses
                                        .filter(path -> path.toString().endsWith(".content-type"))
                                        .count()
                                >= 4,
                        "The proxy should cache package metadata and tarball responses");
            }
            Path secondOutput = tempDir.resolve("second");
            assertEquals(
                    0,
                    importPackage(secondOutput, tempDir.resolve("npm-cache-second"), proxy.url()));
            assertEquals(
                    upstreamRequests,
                    proxy.upstreamRequests(),
                    "A repeat import should use the persistent registry proxy cache");

            assertEquals(
                    0,
                    importPackage(
                            output, tempDir.resolve("npm-cache-third"), proxy.url(), "ms@2.1.2"));
            String combinedEntries = Files.readString(output.resolve("packages.pkl"));
            assertEquals(
                    2, combinedEntries.split("new wstoolDistribution.SourceCode", -1).length - 1);
            assertEquals(1, combinedEntries.split("name = " + '"' + "npm_ms" + '"', -1).length - 1);
            String combinedRoots = Files.readString(output.resolve("npm-dependencies.pkl"));
            assertTrue(combinedRoots.contains("npm_debug"));
            assertTrue(combinedRoots.contains("npm_ms"));
            try (ZipFile zip = new ZipFile(output.resolve("wstool-npm-import.zip").toFile())) {
                assertTrue(zip.getEntry("packages/debug/4.x/package/package.json") != null);
                assertTrue(zip.getEntry("packages/ms/2.x/package/package.json") != null);
            }
            assertEquals(upstreamRequests, proxy.upstreamRequests());
        }
    }

    private int importPackage(Path output, Path npmCache, String registry) {
        return importPackage(output, npmCache, registry, "debug@4.3.4");
    }

    private int importPackage(Path output, Path npmCache, String registry, String... packages) {
        var arguments =
                new ArrayList<String>(
                        List.of(
                                "npm",
                                "--output",
                                output.toString(),
                                "--registry",
                                registry,
                                "--cache",
                                npmCache.toString()));
        arguments.addAll(List.of(packages));
        return new CommandLine(Main.class).execute(arguments.toArray(String[]::new));
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

    @Test
    void npmPackageNamesPreserveTheOriginalName() {
        assertEquals("npm_@scope/pkg", NpmPackageName.wstoolName("@scope/pkg"));
        assertEquals("npm_plain-package", NpmPackageName.wstoolName("plain-package"));
    }
}
