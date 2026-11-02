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

/** A dependency entry in the nested {@code dependencies} section of older npm locks. */
public record NpmLegacyDependency(
        String name,
        @JsonProperty("version") @JsonCodec(NpmVersionCodec.class) NpmVersion npmVersion,
        String resolved,
        String integrity,
        Boolean dev,
        Boolean optional,
        Boolean devOptional,
        Boolean inBundle,
        Boolean hasInstallScript,
        List<String> bundledDependencies,
        Map<String, String> requires,
        Map<String, NpmLegacyDependency> dependencies,
        @JsonAnyProperty Map<String, Object> properties) {
    public NpmLegacyDependency {
        bundledDependencies = Objects.requireNonNullElse(bundledDependencies, List.of());
        requires = Objects.requireNonNullElse(requires, Map.of());
        dependencies = Objects.requireNonNullElse(dependencies, Map.of());
        properties = Objects.requireNonNullElse(properties, Map.of());

        if (name != null) {
            NpmMetadataValidation.validatePackageName(name, "name");
        }
        if (integrity != null) {
            NpmMetadataValidation.validateIntegrity(integrity, "integrity");
        }
        NpmMetadataValidation.validateDependencyMap(requires, "requires");
        bundledDependencies.forEach(
                value -> NpmMetadataValidation.validatePackageName(value, "bundledDependencies"));
        dependencies
                .keySet()
                .forEach(
                        value ->
                                NpmMetadataValidation.validatePackageName(
                                        value, "dependencies." + value));
    }

    /** Returns a non-semver version selector, such as a Git URL, when present. */
    public String versionSpecifier() {
        return npmVersion == null ? null : npmVersion.versionSpecifier();
    }

    /** Returns the semantic version when the lock entry contains one. */
    public Version version() {
        return npmVersion == null ? null : npmVersion.semanticVersion();
    }
}
