package wstool.npm.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import wstool.importer.Main;

class NpmPackageImporterTest {
    @TempDir Path tempDir;

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
            assertTrue(directDependencies.contains("[\"npm_debug\"] = \"4.3.4\""));
            assertFalse(directDependencies.contains("npm_ms"));

            Path debugPackage =
                    output.resolve("package-tree/packages/npm_debug/4.3.4/package/package.json");
            assertTrue(Files.isRegularFile(debugPackage));
            try (ZipFile zip = new ZipFile(output.resolve("wstool-npm-import.zip").toFile())) {
                assertTrue(zip.getEntry("packages/npm_debug/4.3.4/package/package.json") != null);
                assertTrue(zip.getEntry("packages/npm_ms/2.1.2/package/package.json") != null);
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
        }
    }

    private int importPackage(Path output, Path npmCache, String registry) {
        return new CommandLine(Main.class)
                .execute(
                        "npm",
                        "--output",
                        output.toString(),
                        "--registry",
                        registry,
                        "--cache",
                        npmCache.toString(),
                        "debug@4.3.4");
    }
}
