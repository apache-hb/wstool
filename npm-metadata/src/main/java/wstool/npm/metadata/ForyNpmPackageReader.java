package wstool.npm.metadata;

import com.github.zafarkhaja.semver.Version;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;

/** Parses package manifests with Fory and validates npm's documented fields. */
public final class ForyNpmPackageReader implements NpmPackageReader {
    private static final TypeRef<Map<String, Object>> OBJECT_TYPE = new TypeRef<>() {};
    private static final ForyJson JSON = ForyJson.builder().build();

    @Override
    public NpmPackageMetadata read(InputStream input) throws IOException {
        Map<String, Object> properties = parseObject(input);
        Optional<String> name = optionalString(properties, "name");
        if (name.isPresent()) {
            validatePackageName(name.get());
        }
        Optional<Version> version = optionalVersion(properties, "version");
        Optional<String> description = optionalString(properties, "description");
        List<String> keywords = stringList(properties, "keywords");
        Optional<String> type = optionalString(properties, "type");
        if (type.isPresent() && !List.of("commonjs", "module").contains(type.get())) {
            throw invalid("type", "must be 'commonjs' or 'module'");
        }
        Map<String, String> dependencies = stringMap(properties, "dependencies");
        Map<String, String> devDependencies = stringMap(properties, "devDependencies");
        Map<String, String> optionalDependencies = stringMap(properties, "optionalDependencies");
        Map<String, String> peerDependencies = stringMap(properties, "peerDependencies");
        validateDependencyNames(dependencies, "dependencies");
        validateDependencyNames(devDependencies, "devDependencies");
        validateDependencyNames(optionalDependencies, "optionalDependencies");
        validateDependencyNames(peerDependencies, "peerDependencies");
        return new NpmPackageMetadata(
                name,
                version,
                description,
                keywords,
                type,
                dependencies,
                devDependencies,
                optionalDependencies,
                peerDependencies,
                deepImmutable(properties));
    }

    static Map<String, Object> parseObject(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("JSON input stream cannot be null");
        }
        try {
            Map<String, Object> value =
                    JSON.fromJson(
                            new String(input.readAllBytes(), StandardCharsets.UTF_8), OBJECT_TYPE);
            if (value == null) {
                throw new IOException("Expected a JSON object");
            }
            return value;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Invalid npm JSON: " + e.getMessage(), e);
        }
    }

    static void validatePackageName(String name) throws IOException {
        if (name.isEmpty() || name.length() > 214) {
            throw invalid("name", "must be between 1 and 214 characters");
        }
        String packagePart = name;
        if (name.startsWith("@")) {
            int slash = name.indexOf('/');
            if (slash <= 1 || slash == name.length() - 1 || name.indexOf('/', slash + 1) >= 0) {
                throw invalid("name", "scoped names must have the form @scope/package");
            }
            packagePart = name.substring(slash + 1);
            if (!validNameSegment(name.substring(1, slash), true)) {
                throw invalid("name", "contains invalid scope characters");
            }
        } else if (name.indexOf('/') >= 0) {
            throw invalid("name", "unscoped names cannot contain '/'");
        }
        if (!validNameSegment(packagePart, name.startsWith("@"))) {
            throw invalid("name", "contains characters not allowed in npm package names");
        }
        if (!name.startsWith("@") && (name.startsWith(".") || name.startsWith("_"))) {
            throw invalid("name", "unscoped names cannot start with '.' or '_'");
        }
        if (List.of("node_modules", "favicon.ico").contains(name)) {
            throw invalid("name", "is reserved by npm");
        }
    }

    private static boolean validNameSegment(String value, boolean scoped) {
        if (value.isEmpty() || value.startsWith("-")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '-'
                    || character == '_'
                    || character == '.' && (scoped || index > 0))) {
                return false;
            }
        }
        return !value.endsWith(".");
    }

    static void validateDependencyNames(Map<String, String> dependencies, String field)
            throws IOException {
        for (String name : dependencies.keySet()) {
            validatePackageNameForField(name, field);
        }
    }

    private static void validatePackageNameForField(String name, String field) throws IOException {
        try {
            validatePackageName(name);
        } catch (IOException e) {
            throw invalid(field + "." + name, e.getMessage());
        }
    }

    static Optional<String> optionalString(Map<String, Object> object, String field)
            throws IOException {
        Object value = object.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String string)) {
            throw invalid(field, "must be a string");
        }
        return Optional.of(string);
    }

    static Optional<Version> optionalVersion(Map<String, Object> object, String field)
            throws IOException {
        Optional<String> value = optionalString(object, field);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parseNpmVersion(value.get()));
        } catch (RuntimeException e) {
            throw new IOException("Invalid npm " + field + " semantic version: " + value.get(), e);
        }
    }

    static Version parseNpmVersion(String value) {
        String normalized = value.strip();
        if (normalized.startsWith("=")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("v")) {
            normalized = normalized.substring(1);
        }
        return Version.parse(normalized);
    }

    static List<String> stringList(Map<String, Object> object, String field) throws IOException {
        Object value = object.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw invalid(field, "must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (Object element : values) {
            if (!(element instanceof String string)) {
                throw invalid(field, "must contain only strings");
            }
            result.add(string);
        }
        return List.copyOf(result);
    }

    static Map<String, String> stringMap(Map<String, Object> object, String field)
            throws IOException {
        Object value = object.get(field);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> values)) {
            throw invalid(field, "must be an object of string values");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || !(entry.getValue() instanceof String string)
                    || string.isBlank()) {
                throw invalid(field, "must contain package names and non-empty string specs");
            }
            result.put(key, string);
        }
        return Map.copyOf(result);
    }

    static Map<String, Object> deepImmutable(Map<String, Object> properties) {
        Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> result.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), immutableValue(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ForyNpmPackageReader::immutableValue).toList();
        }
        return value;
    }

    static IOException invalid(String field, String reason) {
        return new IOException("Invalid npm package.json field '" + field + "': " + reason);
    }
}
