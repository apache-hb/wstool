package wstool.npm.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.zafarkhaja.semver.Version;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForyNpmPackageLockReaderTest {
    private final NpmPackageLockReader reader = new ForyNpmPackageLockReader();

    @Test
    void readsV3PackageLocationsAndLegacyDependencyTree() throws Exception {
        NpmPackageLock lock = read("package-lock-v3.json");

        assertEquals("@npm/example-package", lock.name().orElseThrow());
        assertEquals(Version.parse("1.2.3"), lock.version().orElseThrow());
        assertEquals(3, lock.lockfileVersion().orElseThrow());
        assertTrue(lock.requires().orElseThrow());
        assertEquals(3, lock.packages().size());

        NpmLockPackage leaf = lock.packages().get("node_modules/@npm/leaf");
        assertEquals(Version.parse("2.1.0"), leaf.version().orElseThrow());
        assertEquals(
                "https://registry.npmjs.org/@npm/leaf/-/leaf-2.1.0.tgz",
                leaf.resolved().orElseThrow());
        assertEquals(Map.of("left-pad", "^1.3.0"), leaf.dependencies());
        @SuppressWarnings("unchecked")
        Map<String, Object> engines = (Map<String, Object>) leaf.properties().get("engines");
        assertEquals(Map.of("node", ">=14"), engines);

        NpmLockPackage legacyLeaf = lock.dependencies().get("@npm/leaf");
        assertEquals(Map.of("left-pad", "^1.3.0"), legacyLeaf.requires());
        assertEquals(
                Version.parse("1.3.0"),
                legacyLeaf.nestedDependencies().get("left-pad").version().orElseThrow());
    }

    @Test
    void readsLegacyLockfileVersionsThatArePackageSpecifiers() throws Exception {
        NpmPackageLock lock = read("package-lock-v1.json");

        assertEquals(1, lock.lockfileVersion().orElseThrow());
        NpmLockPackage dependency = lock.dependencies().get("legacy-git-package");
        assertTrue(dependency.version().isEmpty());
        assertEquals(
                "git+https://github.com/example/legacy-git-package.git#abcdef",
                dependency.versionSpecifier().orElseThrow());
        assertTrue(dependency.nestedDependencies().get("left-pad").dev().orElseThrow());
    }

    @Test
    void rejectsUnsafePackageLocations() {
        assertThrows(IOException.class, () -> read("package-lock-invalid-location.json"));
    }

    @Test
    void acceptsLockfilesWithoutModernPackagesSection() throws Exception {
        NpmPackageLock lock = read("package-lock-v1.json");

        assertTrue(lock.packages().isEmpty());
        assertFalse(lock.dependencies().isEmpty());
    }

    private NpmPackageLock read(String fixture) throws IOException {
        try (InputStream input = resource(fixture)) {
            return reader.read(input);
        }
    }

    private InputStream resource(String fixture) {
        InputStream input = getClass().getResourceAsStream("/npm-metadata/" + fixture);
        if (input == null) {
            throw new IllegalStateException("Missing test resource: " + fixture);
        }
        return input;
    }
}
