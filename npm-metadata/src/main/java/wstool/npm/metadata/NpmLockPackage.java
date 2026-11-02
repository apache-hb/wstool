package wstool.npm.metadata;

import com.github.zafarkhaja.semver.Version;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A package location from a {@code package-lock.json} file. */
public record NpmLockPackage(
        Optional<Version> version,
        Optional<String> versionSpecifier,
        Optional<String> resolved,
        Optional<String> integrity,
        Optional<Boolean> dev,
        Optional<Boolean> optional,
        Optional<Boolean> devOptional,
        Optional<Boolean> inBundle,
        Optional<Boolean> link,
        Optional<Boolean> hasInstallScript,
        Map<String, String> requires,
        Map<String, String> dependencies,
        Map<String, String> optionalDependencies,
        Map<String, String> peerDependencies,
        Map<String, NpmLockPackage> nestedDependencies,
        Map<String, Object> properties) {
    public NpmLockPackage {
        version = version == null ? Optional.empty() : version;
        versionSpecifier = versionSpecifier == null ? Optional.empty() : versionSpecifier;
        resolved = resolved == null ? Optional.empty() : resolved;
        integrity = integrity == null ? Optional.empty() : integrity;
        dev = dev == null ? Optional.empty() : dev;
        optional = optional == null ? Optional.empty() : optional;
        devOptional = devOptional == null ? Optional.empty() : devOptional;
        inBundle = inBundle == null ? Optional.empty() : inBundle;
        link = link == null ? Optional.empty() : link;
        hasInstallScript = hasInstallScript == null ? Optional.empty() : hasInstallScript;
        requires = Map.copyOf(requires);
        dependencies = Map.copyOf(dependencies);
        optionalDependencies = Map.copyOf(optionalDependencies);
        peerDependencies = Map.copyOf(peerDependencies);
        nestedDependencies = Map.copyOf(nestedDependencies);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
