package wstool.npm.metadata;

import com.github.zafarkhaja.semver.Version;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Parses npm package locks with Fory and validates their documented structure. */
public final class ForyNpmPackageLockReader implements NpmPackageLockReader {
    @Override
    public NpmPackageLock read(InputStream input) throws IOException {
        Map<String, Object> properties = ForyNpmPackageReader.parseObject(input);
        Optional<String> name = ForyNpmPackageReader.optionalString(properties, "name");
        if (name.isPresent()) {
            ForyNpmPackageReader.validatePackageName(name.get());
        }
        Optional<Version> version = ForyNpmPackageReader.optionalVersion(properties, "version");
        Optional<Integer> lockfileVersion = optionalInteger(properties, "lockfileVersion");
        Optional<Boolean> requires = optionalBoolean(properties, "requires", "package-lock.json");
        Map<String, NpmLockPackage> packages = packageMap(properties.get("packages"), "packages");
        Map<String, NpmLockPackage> dependencies =
                nestedDependencies(properties.get("dependencies"), "dependencies");
        return new NpmPackageLock(
                name,
                version,
                lockfileVersion,
                requires,
                packages,
                dependencies,
                ForyNpmPackageReader.deepImmutable(properties));
    }

    private Map<String, NpmLockPackage> packageMap(Object value, String field) throws IOException {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> entries)) {
            throw invalid(field, "must be an object mapping package locations to descriptors");
        }
        Map<String, NpmLockPackage> result = new LinkedHashMap<>();
        for (var entry : entries.entrySet()) {
            if (!(entry.getKey() instanceof String location)
                    || !(entry.getValue() instanceof Map<?, ?> descriptor)) {
                throw invalid(field, "must contain string locations and object descriptors");
            }
            validatePackageLocation(location, field);
            result.put(
                    location,
                    packageDescriptor(stringMap(descriptor, field + "." + location), field));
        }
        return Map.copyOf(result);
    }

    private Map<String, NpmLockPackage> nestedDependencies(Object value, String field)
            throws IOException {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> entries)) {
            throw invalid(field, "must be an object of dependency descriptors");
        }
        Map<String, NpmLockPackage> result = new LinkedHashMap<>();
        for (var entry : entries.entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !(entry.getValue() instanceof Map<?, ?> descriptor)) {
                throw invalid(field, "must contain package names and object descriptors");
            }
            try {
                ForyNpmPackageReader.validatePackageName(name);
            } catch (IOException e) {
                throw invalid(field + "." + name, e.getMessage());
            }
            result.put(
                    name,
                    packageDescriptor(
                            stringMap(descriptor, field + "." + name), field + "." + name));
        }
        return Map.copyOf(result);
    }

    private NpmLockPackage packageDescriptor(Map<String, Object> descriptor, String field)
            throws IOException {
        Optional<String> rawVersion = optionalString(descriptor, "version", field);
        Optional<Version> version = Optional.empty();
        Optional<String> versionSpecifier = Optional.empty();
        if (rawVersion.isPresent()) {
            try {
                version = Optional.of(ForyNpmPackageReader.parseNpmVersion(rawVersion.get()));
            } catch (RuntimeException ignored) {
                versionSpecifier = rawVersion;
            }
        }
        Optional<String> resolved = optionalString(descriptor, "resolved", field);
        Optional<String> integrity = optionalString(descriptor, "integrity", field);
        if (integrity.isPresent()) {
            validateIntegrity(integrity.get(), field + ".integrity");
        }

        Map<String, String> requires = stringMapField(descriptor, "requires", field);
        Map<String, String> dependencies = Map.of();
        Map<String, String> optionalDependencies =
                stringMapField(descriptor, "optionalDependencies", field);
        Map<String, String> peerDependencies =
                stringMapField(descriptor, "peerDependencies", field);
        Map<String, NpmLockPackage> nested = Map.of();
        Object dependencyValue = descriptor.get("dependencies");
        if (dependencyValue instanceof Map<?, ?> dependencyEntries) {
            if (allValuesAreStrings(dependencyEntries)) {
                dependencies = stringMapField(descriptor, "dependencies", field);
            } else {
                nested = nestedDependencies(dependencyValue, field + ".dependencies");
            }
        } else if (dependencyValue != null) {
            throw invalid(field + ".dependencies", "must be an object");
        }
        ForyNpmPackageReader.validateDependencyNames(requires, field + ".requires");
        ForyNpmPackageReader.validateDependencyNames(dependencies, field + ".dependencies");
        ForyNpmPackageReader.validateDependencyNames(
                optionalDependencies, field + ".optionalDependencies");
        ForyNpmPackageReader.validateDependencyNames(peerDependencies, field + ".peerDependencies");

        return new NpmLockPackage(
                version,
                versionSpecifier,
                resolved,
                integrity,
                optionalBoolean(descriptor, "dev", field),
                optionalBoolean(descriptor, "optional", field),
                optionalBoolean(descriptor, "devOptional", field),
                optionalBoolean(descriptor, "inBundle", field),
                optionalBoolean(descriptor, "link", field),
                optionalBoolean(descriptor, "hasInstallScript", field),
                requires,
                dependencies,
                optionalDependencies,
                peerDependencies,
                nested,
                ForyNpmPackageReader.deepImmutable(descriptor));
    }

    private boolean allValuesAreStrings(Map<?, ?> values) {
        return values.values().stream().allMatch(String.class::isInstance);
    }

    private void validateIntegrity(String integrity, String field) throws IOException {
        if (integrity.isBlank()) {
            throw invalid(field, "must not be blank");
        }
        for (String token : integrity.split("\\s+")) {
            String[] parts = token.split("-", 2);
            if (parts.length != 2
                    || !Set.of("sha1", "sha256", "sha384", "sha512").contains(parts[0])) {
                throw invalid(field, "must contain supported Subresource Integrity digests");
            }
            String encodedDigest = parts[1].split("\\?", 2)[0];
            try {
                Base64.getDecoder().decode(encodedDigest);
            } catch (IllegalArgumentException e) {
                throw invalid(field, "contains an invalid base64 digest");
            }
        }
    }

    private Map<String, String> stringMapField(
            Map<String, Object> object, String field, String path) throws IOException {
        Object value = object.get(field);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> entries)) {
            throw invalid(path + "." + field, "must be an object of string values");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : entries.entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !(entry.getValue() instanceof String specifier)
                    || specifier.isBlank()) {
                throw invalid(path + "." + field, "must contain non-empty string values");
            }
            result.put(name, specifier);
        }
        return Map.copyOf(result);
    }

    private Optional<String> optionalString(Map<String, Object> object, String field, String path)
            throws IOException {
        Object value = object.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String string)) {
            throw invalid(path + "." + field, "must be a string");
        }
        return Optional.of(string);
    }

    private Optional<Integer> optionalInteger(Map<String, Object> object, String field)
            throws IOException {
        Object value = object.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Number number)
                || number.longValue() < 1
                || number.longValue() > Integer.MAX_VALUE
                || number.doubleValue() != number.longValue()) {
            throw invalid(field, "must be a positive integer");
        }
        return Optional.of(number.intValue());
    }

    private Optional<Boolean> optionalBoolean(Map<String, Object> object, String field, String path)
            throws IOException {
        Object value = object.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Boolean bool)) {
            throw invalid(path + "." + field, "must be a boolean");
        }
        return Optional.of(bool);
    }

    private void validatePackageLocation(String location, String field) throws IOException {
        if (location.isEmpty()) {
            return;
        }
        if (location.startsWith("/")
                || location.contains("\\")
                || Arrays.stream(location.split("/", -1))
                        .anyMatch(
                                segment ->
                                        segment.isEmpty()
                                                || segment.equals(".")
                                                || segment.equals(".."))) {
            throw invalid(field + "." + location, "must be a relative package location");
        }
    }

    private Map<String, Object> stringMap(Map<?, ?> input, String field) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(field, "contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private IOException invalid(String field, String reason) {
        return new IOException("Invalid npm package-lock.json field '" + field + "': " + reason);
    }
}
