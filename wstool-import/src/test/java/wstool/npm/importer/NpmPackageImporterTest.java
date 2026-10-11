package wstool.npm.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pkl.config.java.JavaType;
import picocli.CommandLine;
import wstool.Distribution.PackageSource;
import wstool.Package;
import wstool.importer.Main;
import wstool.simple.PklHelper;

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
            List<PackageSource> entries = readPackageSources(output.resolve("packages.pkl"));
            assertEquals("1.x", findPackage(entries, "npm_@aws-mdaa/sftp-server").getVersion());
            assertTrue(
                    Files.isRegularFile(
                            output.resolve(
                                    "package-tree/packages/@aws-mdaa/sftp-server/1.x/wstool.pkl")));
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
            List<PackageSource> entries = readPackageSources(output.resolve("packages.pkl"));
            assertEquals(2, entries.size());
            assertEquals("4.x", findPackage(entries, "npm_debug").getVersion());
            assertEquals("2.x", findPackage(entries, "npm_ms").getVersion());
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

            List<PackageSource> packageEntries = readPackageSources(output.resolve("packages.pkl"));
            assertEquals(2, packageEntries.size());
            assertEquals("4.x", findPackage(packageEntries, "npm_debug").getVersion());
            assertEquals("2.x", findPackage(packageEntries, "npm_ms").getVersion());
            Map<String, String> directDependencies =
                    readStringMap(output.resolve("npm-dependencies.pkl"), "dependencies");
            assertEquals(Map.of("npm_debug", "4.x"), directDependencies);

            Path debugPackage =
                    output.resolve("package-tree/packages/debug/4.x/package/package.json");
            assertTrue(Files.isRegularFile(debugPackage));
            Package debugConfig =
                    readPackage(output.resolve("package-tree/packages/debug/4.x/wstool.pkl"));
            assertEquals("npm-wstool", debugConfig.getBuildOptions().getTool());
            assertEquals(Map.of("wstool-npm-import", "1.0.0"), debugConfig.getBuildDependencies());
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
            List<PackageSource> combinedEntries =
                    readPackageSources(output.resolve("packages.pkl"));
            assertEquals(2, combinedEntries.size());
            assertEquals("4.x", findPackage(combinedEntries, "npm_debug").getVersion());
            assertEquals("2.x", findPackage(combinedEntries, "npm_ms").getVersion());
            Map<String, String> combinedRoots =
                    readStringMap(output.resolve("npm-dependencies.pkl"), "dependencies");
            assertEquals(Map.of("npm_debug", "4.x", "npm_ms", "2.x"), combinedRoots);
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

    private Map<String, String> readStringMap(Path path, String property) throws Exception {
        try (var pkl = new PklHelper(Map.of())) {
            return Map.copyOf(
                    pkl.evaluate(path)
                            .get(property)
                            .as(JavaType.mapOf(String.class, String.class)));
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

    @Test
    void npmPackageNamesPreserveTheOriginalName() {
        assertEquals("npm_@scope/pkg", NpmPackageName.wstoolName("@scope/pkg"));
        assertEquals("npm_plain-package", NpmPackageName.wstoolName("plain-package"));
    }
}
