package wstool.npm.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;

/** Uses npm's resolver to produce a reproducible lockfile and package metadata. */
public final class NpmImporter {
    private final String npmExecutable;
    private final String registryUrl;
    private final ForyJson json;
    private static final TypeRef<Map<String, Object>> JSON_OBJECT_TYPE = new TypeRef<>() {};

    public NpmImporter(String npmExecutable, String registryUrl) {
        this.npmExecutable = npmExecutable;
        this.registryUrl = registryUrl;
        this.json = ForyJson.builder().build();
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
        Map<String, Object> root =
                json.fromJson(Files.readString(lockfile, StandardCharsets.UTF_8), JSON_OBJECT_TYPE);
        Object packageData = root.get("packages");
        if (!(packageData instanceof Map<?, ?> packageEntries)) {
            throw new IOException("Unsupported npm lockfile: expected a packages object");
        }

        List<NpmPackage> result = new ArrayList<>();
        for (var field : packageEntries.entrySet()) {
            String lockPath = String.valueOf(field.getKey());
            if (!lockPath.startsWith("node_modules/")) {
                continue;
            }
            if (!(field.getValue() instanceof Map<?, ?> metadata)) {
                continue;
            }
            String name = stringValue(metadata.get("name"), packageNameFromLockPath(lockPath));
            String version = stringValue(metadata.get("version"), null);
            if (version == null) {
                continue;
            }
            if (Boolean.TRUE.equals(metadata.get("inBundle"))) {
                continue;
            }
            Map<String, String> packageDependencies = new LinkedHashMap<>();
            addDependencies(packageDependencies, metadata.get("dependencies"));
            addDependencies(packageDependencies, metadata.get("optionalDependencies"));
            addDependencies(packageDependencies, metadata.get("peerDependencies"));
            removeBundledDependencies(packageDependencies, metadata.get("bundleDependencies"));
            removeBundledDependencies(packageDependencies, metadata.get("bundledDependencies"));
            result.add(
                    new NpmPackage(
                            name,
                            version,
                            stringValue(metadata.get("resolved"), null),
                            stringValue(metadata.get("integrity"), null),
                            packageDependencies));
        }
        return List.copyOf(result);
    }

    private void removeBundledDependencies(Map<String, String> dependencies, Object bundledValue) {
        if (bundledValue instanceof List<?> bundledDependencies) {
            for (Object dependency : bundledDependencies) {
                if (dependency instanceof String name) {
                    dependencies.remove(name);
                }
            }
        }
    }

    private String stringValue(Object value, String defaultValue) {
        return value instanceof String string ? string : defaultValue;
    }

    private void addDependencies(Map<String, String> dependencies, Object value) {
        if (value instanceof Map<?, ?> values) {
            values.forEach(
                    (name, range) -> {
                        if (name instanceof String dependencyName
                                && range instanceof String dependencyRange) {
                            dependencies.put(dependencyName, dependencyRange);
                        }
                    });
        }
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
