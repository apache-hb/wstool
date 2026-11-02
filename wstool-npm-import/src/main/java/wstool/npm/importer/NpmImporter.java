package wstool.npm.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;
import wstool.npm.metadata.NpmLockPackage;
import wstool.npm.metadata.NpmPackageLock;
import wstool.npm.metadata.NpmPackageLockReader;
import wstool.npm.metadata.impl.ForyNpmPackageLockReader;

/** Uses npm's resolver to produce a reproducible lockfile and package metadata. */
public final class NpmImporter {
    private final String npmExecutable;
    private final String registryUrl;
    private final ForyJson json;
    private final NpmPackageLockReader packageLockReader;
    private static final TypeRef<Map<String, Object>> JSON_OBJECT_TYPE = new TypeRef<>() {};

    public NpmImporter(String npmExecutable, String registryUrl) {
        this.npmExecutable = npmExecutable;
        this.registryUrl = registryUrl;
        this.json = ForyJson.builder().build();
        this.packageLockReader = new ForyNpmPackageLockReader(json);
    }

    public NpmImportResult importPackages(List<String> packageSpecifications, Path outputDirectory)
            throws IOException, InterruptedException {
        if (packageSpecifications.isEmpty()) {
            throw new IllegalArgumentException("At least one npm package is required");
        }

        Files.createDirectories(outputDirectory);
        Path manifest = outputDirectory.resolve("package.json");
        Path lockfile = outputDirectory.resolve("package-lock.json");
        Map<String, String> dependencies = new TreeMap<>();
        for (String specification : packageSpecifications) {
            PackageSpecification parsed = PackageSpecification.parse(specification);
            String previous = dependencies.putIfAbsent(parsed.name(), parsed.versionRange());
            if (previous != null && !previous.equals(parsed.versionRange())) {
                throw new IllegalArgumentException(
                        "Conflicting versions requested for "
                                + parsed.name()
                                + ": "
                                + previous
                                + " and "
                                + parsed.versionRange());
            }
        }

        Map<String, Object> packageJson = new LinkedHashMap<>();
        packageJson.put("name", "wstool-npm-import");
        packageJson.put("version", "1.0.0");
        packageJson.put("dependencies", dependencies);
        Files.writeString(
                manifest, json.toJson(packageJson, JSON_OBJECT_TYPE) + System.lineSeparator());

        List<String> command =
                new ArrayList<>(
                        List.of(
                                npmExecutable,
                                "install",
                                "--package-lock-only",
                                "--ignore-scripts",
                                "--no-audit",
                                "--no-fund",
                                "--silent",
                                "--allow-remote=all",
                                "--registry",
                                registryUrl));
        Process process =
                new ProcessBuilder(command).directory(outputDirectory.toFile()).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("npm install failed with exit code " + exitCode);
        }
        if (!Files.isRegularFile(lockfile)) {
            throw new IOException("npm completed without creating " + lockfile);
        }
        return new NpmImportResult(manifest, lockfile, readPackages(lockfile));
    }

    private List<NpmPackage> readPackages(Path lockfile) throws IOException {
        NpmPackageLock packageLock;
        try (InputStream input = Files.newInputStream(lockfile)) {
            packageLock = packageLockReader.read(input);
        }

        Map<String, String> versionsByLockPath = new LinkedHashMap<>();
        for (var entry : packageLock.packages().entrySet()) {
            String lockPath = entry.getKey();
            if (lockPath.startsWith("node_modules/")) {
                String version = versionString(entry.getValue());
                if (version != null) {
                    versionsByLockPath.put(lockPath, version);
                }
            }
        }

        List<NpmPackage> result = new ArrayList<>();
        for (var entry : packageLock.packages().entrySet()) {
            String lockPath = entry.getKey();
            if (!lockPath.startsWith("node_modules/")) {
                continue;
            }
            NpmLockPackage metadata = entry.getValue();
            String name =
                    metadata.name() == null ? packageNameFromLockPath(lockPath) : metadata.name();
            String version = versionString(metadata);
            if (version == null) {
                continue;
            }
            if (Boolean.TRUE.equals(metadata.inBundle())) {
                continue;
            }
            Map<String, String> packageDependencies = new LinkedHashMap<>();
            packageDependencies.putAll(metadata.dependencies());
            packageDependencies.putAll(metadata.optionalDependencies());
            packageDependencies.putAll(metadata.peerDependencies());
            metadata.bundledDependencies().forEach(packageDependencies::remove);
            packageDependencies.replaceAll(
                    (dependencyName, ignoredRange) ->
                            resolveDependencyVersion(lockPath, dependencyName, versionsByLockPath));
            packageDependencies.values().removeIf(dependencyVersion -> dependencyVersion == null);
            result.add(
                    new NpmPackage(
                            name,
                            version,
                            metadata.resolved(),
                            metadata.integrity(),
                            packageDependencies));
        }
        return List.copyOf(result);
    }

    private String resolveDependencyVersion(
            String packageLockPath, String dependencyName, Map<String, String> versionsByLockPath) {
        String candidate = packageLockPath + "/node_modules/" + dependencyName;
        String resolvedVersion = versionsByLockPath.get(candidate);
        if (resolvedVersion != null) {
            return resolvedVersion;
        }

        String parentPath = packageLockPath;
        while (true) {
            int parentPackageStart = parentPath.lastIndexOf("/node_modules/");
            if (parentPackageStart >= 0) {
                parentPath = parentPath.substring(0, parentPackageStart);
            } else if (parentPath.startsWith("node_modules/")) {
                parentPath = "";
            } else {
                return null;
            }

            candidate =
                    parentPath.isEmpty()
                            ? "node_modules/" + dependencyName
                            : parentPath + "/node_modules/" + dependencyName;
            resolvedVersion = versionsByLockPath.get(candidate);
            if (resolvedVersion != null) {
                return resolvedVersion;
            }
        }
    }

    private String versionString(NpmLockPackage metadata) {
        return metadata.version() == null
                ? metadata.versionSpecifier()
                : metadata.version().toString();
    }

    private String packageNameFromLockPath(String lockPath) {
        int packageStart = lockPath.lastIndexOf("node_modules/") + "node_modules/".length();
        String path = lockPath.substring(packageStart);
        if (path.startsWith("@")) {
            int scopeEnd = path.indexOf('/');
            int packageEnd = path.indexOf('/', scopeEnd + 1);
            return packageEnd < 0 ? path : path.substring(0, packageEnd);
        }
        int packageEnd = path.indexOf('/');
        return packageEnd < 0 ? path : path.substring(0, packageEnd);
    }

    private record PackageSpecification(String name, String versionRange) {
        private static PackageSpecification parse(String value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("npm package specification cannot be blank");
            }
            int separator = value.indexOf(':');
            String name = separator <= 0 ? value : value.substring(0, separator);
            String version = separator <= 0 ? "latest" : value.substring(separator + 1);
            if (name.isBlank()
                    || name.chars().anyMatch(Character::isWhitespace)
                    || version.isBlank()
                    || !isValidPackageName(name)) {
                throw new IllegalArgumentException("Invalid npm package specification: " + value);
            }
            return new PackageSpecification(name, version);
        }

        private static boolean isValidPackageName(String name) {
            if (name.startsWith("@")) {
                int separator = name.indexOf('/');
                return separator > 1
                        && separator < name.length() - 1
                        && name.indexOf('/', separator + 1) < 0;
            }
            return name.indexOf('/') < 0 && name.indexOf('@') < 0;
        }
    }
}
