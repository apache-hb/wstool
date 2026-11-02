package wstool.npm.metadata;

import com.github.zafarkhaja.semver.Version;
import java.util.Map;
import java.util.Objects;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonProperty;
import wstool.npm.metadata.impl.NpmMetadataValidation;
import wstool.npm.metadata.impl.NpmVersionCodec;

/** Strongly typed representation of an npm package lock. */
public record NpmPackageLock(
        String name,
        @JsonProperty("version") @JsonCodec(NpmVersionCodec.class) NpmVersion npmVersion,
        Integer lockfileVersion,
        Boolean requires,
        Map<String, NpmLockPackage> packages,
        Map<String, NpmLegacyDependency> dependencies,
        @JsonAnyProperty Map<String, Object> properties) {
    public NpmPackageLock {
        packages = Objects.requireNonNullElse(packages, Map.of());
        dependencies = Objects.requireNonNullElse(dependencies, Map.of());
        properties = Objects.requireNonNullElse(properties, Map.of());

        if (name != null) {
            NpmMetadataValidation.validatePackageName(name, "name");
        }
        if (npmVersion != null && npmVersion.semanticVersion() == null) {
            throw new IllegalArgumentException("version must be a semantic version");
        }
        if (lockfileVersion != null && lockfileVersion < 1) {
            throw new IllegalArgumentException("lockfileVersion must be a positive integer");
        }
        packages.keySet()
                .forEach(
                        key ->
                                NpmMetadataValidation.validatePackageLocation(
                                        key, "packages." + key));
        dependencies
                .keySet()
                .forEach(
                        key ->
                                NpmMetadataValidation.validatePackageName(
                                        key, "dependencies." + key));
    }

    /** Returns the semantic version from the lockfile root. */
    public Version version() {
        return npmVersion == null ? null : npmVersion.semanticVersion();
    }
}
