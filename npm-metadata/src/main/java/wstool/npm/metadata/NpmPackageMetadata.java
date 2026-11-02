package wstool.npm.metadata;

import com.github.zafarkhaja.semver.Version;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Strongly typed fields from an npm {@code package.json} manifest. */
public record NpmPackageMetadata(
        Optional<String> name,
        Optional<Version> version,
        Optional<String> description,
        List<String> keywords,
        Optional<String> type,
        Map<String, String> dependencies,
        Map<String, String> devDependencies,
        Map<String, String> optionalDependencies,
        Map<String, String> peerDependencies,
        Map<String, Object> properties) {
    public NpmPackageMetadata {
        name = name == null ? Optional.empty() : name;
        version = version == null ? Optional.empty() : version;
        description = description == null ? Optional.empty() : description;
        keywords = List.copyOf(keywords);
        type = type == null ? Optional.empty() : type;
        dependencies = Map.copyOf(dependencies);
        devDependencies = Map.copyOf(devDependencies);
        optionalDependencies = Map.copyOf(optionalDependencies);
        peerDependencies = Map.copyOf(peerDependencies);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
