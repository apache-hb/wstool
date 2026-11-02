package wstool.npm.metadata;

import com.github.zafarkhaja.semver.Version;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Strongly typed representation of an npm package lock. */
public record NpmPackageLock(
        Optional<String> name,
        Optional<Version> version,
        Optional<Integer> lockfileVersion,
        Optional<Boolean> requires,
        Map<String, NpmLockPackage> packages,
        Map<String, NpmLockPackage> dependencies,
        Map<String, Object> properties) {
    public NpmPackageLock {
        name = name == null ? Optional.empty() : name;
        version = version == null ? Optional.empty() : version;
        lockfileVersion = lockfileVersion == null ? Optional.empty() : lockfileVersion;
        requires = requires == null ? Optional.empty() : requires;
        packages = Map.copyOf(packages);
        dependencies = Map.copyOf(dependencies);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
