package wstool.npm.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.zafarkhaja.semver.Version;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForyNpmPackageReaderTest {
    private final NpmPackageReader reader = new ForyNpmPackageReader();

    @Test
    void readsTypedManifestFieldsAndPreservesAdditionalProperties() throws Exception {
        NpmPackageMetadata metadata = read("package.json");

        assertEquals("@npm/example-package", metadata.name().orElseThrow());
        assertEquals(Version.parse("1.2.3-beta.2+build.7"), metadata.version().orElseThrow());
        assertEquals("A package manifest fixture", metadata.description().orElseThrow());
        assertEquals(List.of("npm", "metadata"), metadata.keywords());
        assertEquals("module", metadata.type().orElseThrow());
        assertEquals(Map.of("@npm/leaf", "^2.0.0", "left-pad", "~1.3.0"), metadata.dependencies());
        assertEquals(Map.of("typescript", "^5.0.0"), metadata.devDependencies());
        assertEquals(Map.of("optional-helper", "latest"), metadata.optionalDependencies());
        assertEquals(Map.of("peer-helper", ">=1.0.0 <3.0.0"), metadata.peerDependencies());

        @SuppressWarnings("unchecked")
        Map<String, Object> exports = (Map<String, Object>) metadata.properties().get("exports");
        assertEquals(Map.of(".", "./index.js", "./feature", "./feature.js"), exports);
        assertEquals(
                Map.of("enabled", true, "labels", List.of("one", "two")),
                metadata.properties().get("customMetadata"));
    }

    @Test
    void rejectsVersionsThatAreNotValidSemver() {
        assertThrows(IOException.class, () -> read("package-invalid-version.json"));
    }

    @Test
    void rejectsInvalidDependencyPackageNames() {
        assertThrows(IOException.class, () -> read("package-invalid-dependency.json"));
    }

    @Test
    void permitsManifestsWithoutOptionalNameAndVersion() throws Exception {
        NpmPackageMetadata metadata = read("package-minimal.json");

        assertTrue(metadata.name().isEmpty());
        assertTrue(metadata.version().isEmpty());
        assertEquals(Map.of(), metadata.dependencies());
    }

    private NpmPackageMetadata read(String fixture) throws IOException {
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
