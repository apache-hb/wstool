package wstool.npm.metadata;

import com.github.zafarkhaja.semver.Version;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonProperty;
import wstool.npm.metadata.impl.NpmMetadataValidation;
import wstool.npm.metadata.impl.NpmVersionCodec;

/** Strongly typed fields from an npm {@code package.json} manifest. */
public record NpmPackageMetadata(
        String name,
        @JsonProperty("version") @JsonCodec(NpmVersionCodec.class) NpmVersion npmVersion,
        String description,
        List<String> keywords,
        String type,
        Map<String, String> dependencies,
        Map<String, String> devDependencies,
        Map<String, String> optionalDependencies,
        Map<String, String> peerDependencies,
        @JsonAnyProperty Map<String, Object> properties) {

    public NpmPackageMetadata {
        keywords = Objects.requireNonNullElse(keywords, List.of());
        dependencies = Objects.requireNonNullElse(dependencies, Map.of());
        devDependencies = Objects.requireNonNullElse(devDependencies, Map.of());
        optionalDependencies = Objects.requireNonNullElse(optionalDependencies, Map.of());
        peerDependencies = Objects.requireNonNullElse(peerDependencies, Map.of());
        properties = Objects.requireNonNullElse(properties, Map.of());

        if (name != null) {
            NpmMetadataValidation.validatePackageName(name, "name");
        }
        if (npmVersion != null && npmVersion.semanticVersion() == null) {
            throw new IllegalArgumentException("version must be a semantic version");
        }
        if (type != null && !List.of("commonjs", "module").contains(type)) {
            throw new IllegalArgumentException("type must be 'commonjs' or 'module'");
        }
        NpmMetadataValidation.validateDependencyMap(dependencies, "dependencies");
        NpmMetadataValidation.validateDependencyMap(devDependencies, "devDependencies");
        NpmMetadataValidation.validateDependencyMap(optionalDependencies, "optionalDependencies");
        NpmMetadataValidation.validateDependencyMap(peerDependencies, "peerDependencies");
    }

    /** Returns the semantic version from the manifest. */
    public Version version() {
        return npmVersion == null ? null : npmVersion.semanticVersion();
    }
}
