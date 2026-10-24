package wstool.npm.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.test.components.NpmRegistryManager;

@Testcontainers(disabledWithoutDocker = true)
class NpmImporterTest {
    private static final TypeRef<Map<String, Object>> JSON_OBJECT_TYPE = new TypeRef<>() {};

    @Container private static final GenericContainer<?> REGISTRY = NpmRegistryManager.container();

    @TempDir static Path npmConfigDirectory;

    @TempDir Path tempDirectory;

    @BeforeAll
    static void publishPackageFixtures() throws Exception {
        NpmRegistryManager.publishPackage(REGISTRY, packageFixture("leaf"), npmConfigDirectory);
        NpmRegistryManager.publishPackage(REGISTRY, packageFixture("root"), npmConfigDirectory);
    }

    @Test
    void writesManifestAndReadsResolvedPackagesFromRegistryLockfile() throws Exception {
        NpmImportResult result =
                importer()
                        .importPackages(
                                List.of("@wstool-test/root:1.0.0"), tempDirectory.resolve("out"));

        assertTrue(Files.isRegularFile(result.packageManifest()));
        assertTrue(Files.isRegularFile(result.lockfile()));
        assertEquals(
                Map.of("@wstool-test/root", "1.0.0"),
                readObject(result.packageManifest()).get("dependencies"));
        assertEquals(2, result.packages().size());

        NpmPackage rootPackage = packageNamed(result.packages(), "@wstool-test/root");
        assertEquals("1.0.0", rootPackage.version());
        assertEquals(Map.of("@wstool-test/leaf", "2.1.0"), rootPackage.dependencies());
        assertTrue(URI.create(rootPackage.tarballUrl()).isAbsolute());
        assertTrue(rootPackage.integrity().startsWith("sha512-"));

        NpmPackage leafPackage = packageNamed(result.packages(), "@wstool-test/leaf");
        assertEquals("2.1.0", leafPackage.version());
        assertEquals(Map.of(), leafPackage.dependencies());
    }

    @Test
    void acceptsScopedPackageNamesWithoutARequestedVersion() throws Exception {
        NpmImportResult result =
                importer()
                        .importPackages(
                                List.of("@wstool-test/root"), tempDirectory.resolve("latest"));

        assertEquals(
                Map.of("@wstool-test/root", "latest"),
                readObject(result.packageManifest()).get("dependencies"));
    }

    @Test
    void rejectsEmptyPackageList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> importer().importPackages(List.of(), tempDirectory.resolve("empty")));
    }

    @Test
    void rejectsConflictingVersionsForTheSamePackage() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        importer()
                                .importPackages(
                                        List.of(
                                                "@wstool-test/root:1.0.0",
                                                "@wstool-test/root:2.0.0"),
                                        tempDirectory.resolve("conflict")));
    }

    @Test
    void rejectsMalformedScopedPackageNames() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        importer()
                                .importPackages(
                                        List.of("@wstool-test"),
                                        tempDirectory.resolve("malformed")));
    }

    private NpmImporter importer() {
        return new NpmImporter("npm", NpmRegistryManager.registryUri(REGISTRY).toString());
    }

    private static Path packageFixture(String name) throws Exception {
        var resource = NpmImporterTest.class.getResource("/npm-registry/packages/" + name);
        if (resource == null) {
            throw new IOException("Missing npm package fixture: " + name);
        }
        return Path.of(resource.toURI());
    }

    private NpmPackage packageNamed(List<NpmPackage> packages, String name) {
        return packages.stream()
                .filter(npmPackage -> npmPackage.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, Object> readObject(Path path) throws IOException {
        return ForyJson.builder().build().fromJson(Files.readString(path), JSON_OBJECT_TYPE);
    }
}
