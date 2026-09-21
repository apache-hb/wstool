package wstool.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import wstool.common.model.PackageDescriptor;
import wstool.common.model.PackageVersion;

/** Reads the small, intentionally stable subset of Pkl used by wstool package files. */
public final class PackageDescriptorReader {
    private static final Pattern PACKAGE_FIELD = Pattern.compile("(?s)package\\s*\\{(.*?)\\}");
    private static final Pattern FIELD =
            Pattern.compile("(?m)^\\s*(name|version|buildTool)\\s*(?::|=)\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern MAP =
            Pattern.compile(
                    "(?s)(buildDependencies|dependencies|testDependencies|runtimeDependencies|overrideDependencies)\\s*\\{(.*?)\\}");
    private static final Pattern MAP_ENTRY =
            Pattern.compile("\\[\\s*\\\"([^\\\"]+)\\\"\\s*]\\s*=\\s*\\\"([^\\\"]+)\\\"");

    private PackageDescriptorReader() {}

    public static PackageDescriptor read(Path packageDirectory) {
        var file = packageDirectory.resolve("wstool.pkl");
        if (!Files.exists(file)) {
            file = packageDirectory.resolve("Package.pkl");
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("No wstool.pkl found in " + packageDirectory);
        }

        try {
            var text = Files.readString(file);
            var packageBody = matchBody(PACKAGE_FIELD, text);
            var name = field(packageBody, "name");
            var version = field(packageBody, "version");
            var buildTool = optionalField(text, "buildTool", "cmake");
            var maps = new LinkedHashMap<String, Map<String, String>>();
            var matcher = MAP.matcher(text);
            while (matcher.find()) {
                maps.put(matcher.group(1), parseMap(matcher.group(2)));
            }
            return new PackageDescriptor(
                    new PackageVersion(name, version),
                    buildTool,
                    maps.getOrDefault("buildDependencies", Map.of()),
                    maps.getOrDefault("dependencies", Map.of()),
                    maps.getOrDefault("testDependencies", Map.of()),
                    maps.getOrDefault("runtimeDependencies", Map.of()),
                    maps.getOrDefault("overrideDependencies", Map.of()),
                    packageDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + file, e);
        }
    }

    private static String matchBody(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Package descriptor has no package { name, version } block");
        }
        return matcher.group(1);
    }

    private static String field(String text, String name) {
        return optionalField(text, name, null);
    }

    private static String optionalField(String text, String name, String defaultValue) {
        var matcher = FIELD.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        throw new IllegalArgumentException("Package descriptor is missing " + name);
    }

    private static Map<String, String> parseMap(String text) {
        var result = new LinkedHashMap<String, String>();
        var matcher = MAP_ENTRY.matcher(text);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return Map.copyOf(result);
    }
}
