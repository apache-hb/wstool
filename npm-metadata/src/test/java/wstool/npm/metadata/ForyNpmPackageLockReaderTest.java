package wstool.npm.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.zafarkhaja.semver.Version;
import java.io.InputStream;
import java.util.Map;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.junit.jupiter.api.Test;
import wstool.npm.metadata.impl.ForyNpmPackageLockReader;

class ForyNpmPackageLockReaderTest {
    private final NpmPackageLockReader reader =
            new ForyNpmPackageLockReader(ForyJson.builder().build());

    @Test
    void readsV3PackageLocationsAndLegacyDependencyTree() throws Exception {
        NpmPackageLock lock = read("package-lock-v3.json");

        assertEquals("@npm/example-package", lock.name());
        assertEquals(Version.parse("1.2.3"), lock.version());
        assertEquals(3, lock.lockfileVersion());
        assertTrue(lock.requires());
        assertEquals(3, lock.packages().size());

        NpmLockPackage leaf = lock.packages().get("node_modules/@npm/leaf");
        assertEquals(Version.parse("2.1.0"), leaf.version());
        assertEquals("https://registry.npmjs.org/@npm/leaf/-/leaf-2.1.0.tgz", leaf.resolved());
        assertEquals(Map.of("left-pad", "^1.3.0"), leaf.dependencies());
        @SuppressWarnings("unchecked")
        Map<String, Object> engines = (Map<String, Object>) leaf.properties().get("engines");
        assertEquals(Map.of("node", ">=14"), engines);

        NpmLegacyDependency legacyLeaf = lock.dependencies().get("@npm/leaf");
        assertEquals(Map.of("left-pad", "^1.3.0"), legacyLeaf.requires());
        assertEquals(Version.parse("1.3.0"), legacyLeaf.dependencies().get("left-pad").version());
    }

    @Test
    void readsLegacyLockfileVersionsThatArePackageSpecifiers() throws Exception {
        NpmPackageLock lock = read("package-lock-v1.json");

        assertEquals(1, lock.lockfileVersion());
        NpmLegacyDependency dependency = lock.dependencies().get("legacy-git-package");
        assertNull(dependency.version());
        assertEquals(
                "git+https://github.com/example/legacy-git-package.git#abcdef",
                dependency.versionSpecifier());
        assertTrue(dependency.dependencies().get("left-pad").dev());
    }

    @Test
    void rejectsUnsafePackageLocations() {
        assertThrows(ForyJsonException.class, () -> read("package-lock-invalid-location.json"));
    }

    @Test
    void acceptsLockfilesWithoutModernPackagesSection() throws Exception {
        NpmPackageLock lock = read("package-lock-v1.json");

        assertTrue(lock.packages().isEmpty());
        assertFalse(lock.dependencies().isEmpty());
    }

    private NpmPackageLock read(String fixture) throws Exception {
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
