package wstool.npm.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.zafarkhaja.semver.Version;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.junit.jupiter.api.Test;
import wstool.npm.metadata.impl.ForyNpmPackageReader;

class ForyNpmPackageReaderTest {
    private final NpmPackageReader reader = new ForyNpmPackageReader(ForyJson.builder().build());

    @Test
    void readsTypedManifestFieldsAndPreservesAdditionalProperties() throws Exception {
        NpmPackageMetadata metadata = read("package.json");

        assertEquals("@npm/example-package", metadata.name());
        assertEquals(Version.parse("1.2.3-beta.2+build.7"), metadata.version());
        assertEquals("A package manifest fixture", metadata.description());
        assertEquals(List.of("npm", "metadata"), metadata.keywords());
        assertEquals("module", metadata.type());
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
        assertThrows(ForyJsonException.class, () -> read("package-invalid-version.json"));
    }

    @Test
    void rejectsInvalidDependencyPackageNames() {
        assertThrows(ForyJsonException.class, () -> read("package-invalid-dependency.json"));
    }

    @Test
    void permitsManifestsWithoutOptionalNameAndVersion() throws Exception {
        NpmPackageMetadata metadata = read("package-minimal.json");

        assertNull(metadata.name());
        assertNull(metadata.version());
        assertEquals(Map.of(), metadata.dependencies());
    }

    private NpmPackageMetadata read(String fixture) throws Exception {
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
