package wstool.npm.importer;

import java.util.Map;

/** Package metadata resolved from an npm package lock. */
public record NpmPackage(
        String name,
        String version,
        String tarballUrl,
        String integrity,
        Map<String, String> dependencies) {
    public NpmPackage {
        dependencies = Map.copyOf(dependencies);
    }
}
