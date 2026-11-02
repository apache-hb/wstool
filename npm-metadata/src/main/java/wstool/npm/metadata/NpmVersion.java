package wstool.npm.metadata;

import com.github.zafarkhaja.semver.Version;
import wstool.npm.metadata.impl.NpmMetadataValidation;

/** Preserves an npm version string and exposes its semantic version when it has one. */
public record NpmVersion(String value) {
    public NpmVersion {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    /** Returns the parsed semantic version, or null for a non-semver selector. */
    public Version semanticVersion() {
        return NpmMetadataValidation.tryParseNpmVersion(value);
    }

    /** Returns a non-semver selector, such as a Git URL, or null for a semantic version. */
    public String versionSpecifier() {
        return semanticVersion() == null ? value : null;
    }
}
