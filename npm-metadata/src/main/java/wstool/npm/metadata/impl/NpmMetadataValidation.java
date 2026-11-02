package wstool.npm.metadata.impl;

import com.github.zafarkhaja.semver.Version;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Domain validation shared by the npm metadata record constructors. */
public final class NpmMetadataValidation {
    private NpmMetadataValidation() {}

    public static void validatePackageName(String name, String field) {
        if (name.isEmpty() || name.length() > 214) {
            throw new IllegalArgumentException(field + " must be between 1 and 214 characters");
        }
        String packagePart = name;
        if (name.startsWith("@")) {
            int slash = name.indexOf('/');
            if (slash <= 1 || slash == name.length() - 1 || name.indexOf('/', slash + 1) >= 0) {
                throw new IllegalArgumentException(field + " must have the form @scope/package");
            }
            packagePart = name.substring(slash + 1);
            if (!validNameSegment(name.substring(1, slash), true)) {
                throw new IllegalArgumentException(field + " contains invalid scope characters");
            }
        } else if (name.indexOf('/') >= 0) {
            throw new IllegalArgumentException(field + " cannot contain '/' when unscoped");
        }
        if (!validNameSegment(packagePart, name.startsWith("@"))) {
            throw new IllegalArgumentException(
                    field + " contains invalid npm package name characters");
        }
        if (!name.startsWith("@") && (name.startsWith(".") || name.startsWith("_"))) {
            throw new IllegalArgumentException(
                    field + " cannot start with '.' or '_' when unscoped");
        }
        if (List.of("node_modules", "favicon.ico").contains(name)) {
            throw new IllegalArgumentException(field + " is reserved by npm");
        }
    }

    public static void validateDependencyMap(Map<String, String> dependencies, String field) {
        dependencies.forEach(
                (name, specifier) -> {
                    validatePackageName(name, field + "." + name);
                    if (specifier.isBlank()) {
                        throw new IllegalArgumentException(
                                field + "." + name + " must not be blank");
                    }
                });
    }

    public static Version parseNpmVersion(String value) {
        String normalized = value.strip();
        if (normalized.startsWith("=")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("v")) {
            normalized = normalized.substring(1);
        }
        try {
            return Version.parse(normalized);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid semantic version: " + value, e);
        }
    }

    public static Version tryParseNpmVersion(String value) {
        try {
            return parseNpmVersion(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void validatePackageLocation(String location, String field) {
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
            throw new IllegalArgumentException(field + " must be a relative package location");
        }
    }

    public static void validateIntegrity(String integrity, String field) {
        if (integrity.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (String token : integrity.split("\\s+")) {
            String[] parts = token.split("-", 2);
            if (parts.length != 2
                    || !Set.of("sha1", "sha256", "sha384", "sha512").contains(parts[0])) {
                throw new IllegalArgumentException(
                        field + " must contain supported Subresource Integrity digests");
            }
            String encodedDigest = parts[1].split("\\?", 2)[0];
            try {
                Base64.getDecoder().decode(encodedDigest);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(field + " contains an invalid base64 digest", e);
            }
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
}
