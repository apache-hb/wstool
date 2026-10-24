package wstool.npm.importer;

import java.nio.file.Path;
import java.util.List;

/** Files and package metadata produced by an npm import. */
public record NpmImportResult(Path packageManifest, Path lockfile, List<NpmPackage> packages) {
    public NpmImportResult {
        packages = List.copyOf(packages);
    }
}
