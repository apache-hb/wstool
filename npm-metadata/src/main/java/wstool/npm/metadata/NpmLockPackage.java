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

/** A package location from a {@code package-lock.json} file. */
public record NpmLockPackage(
        String name,
        @JsonProperty("version") @JsonCodec(NpmVersionCodec.class) NpmVersion npmVersion,
        String resolved,
        String integrity,
        Boolean dev,
        Boolean optional,
        Boolean devOptional,
        Boolean inBundle,
        Boolean link,
        Boolean hasInstallScript,
        List<String> bundledDependencies,
        Map<String, String> requires,
        Map<String, String> dependencies,
        Map<String, String> optionalDependencies,
        Map<String, String> peerDependencies,
        @JsonAnyProperty Map<String, Object> properties) {
    public NpmLockPackage {
        bundledDependencies = Objects.requireNonNullElse(bundledDependencies, List.of());
        requires = Objects.requireNonNullElse(requires, Map.of());
        dependencies = Objects.requireNonNullElse(dependencies, Map.of());
        optionalDependencies = Objects.requireNonNullElse(optionalDependencies, Map.of());
        peerDependencies = Objects.requireNonNullElse(peerDependencies, Map.of());
        properties = Objects.requireNonNullElse(properties, Map.of());

        if (name != null) {
            NpmMetadataValidation.validatePackageName(name, "name");
        }
        if (integrity != null) {
            NpmMetadataValidation.validateIntegrity(integrity, "integrity");
        }
        NpmMetadataValidation.validateDependencyMap(requires, "requires");
        NpmMetadataValidation.validateDependencyMap(dependencies, "dependencies");
        NpmMetadataValidation.validateDependencyMap(optionalDependencies, "optionalDependencies");
        NpmMetadataValidation.validateDependencyMap(peerDependencies, "peerDependencies");
        bundledDependencies.forEach(
                value -> NpmMetadataValidation.validatePackageName(value, "bundledDependencies"));
    }

    /** Returns the semantic version when the lock entry contains one. */
    public Version version() {
        return npmVersion == null ? null : npmVersion.semanticVersion();
    }

    /** Returns a non-semver version selector, such as a Git URL, when present. */
    public String versionSpecifier() {
        return npmVersion == null ? null : npmVersion.versionSpecifier();
    }
}
