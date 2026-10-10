package wstool.npm.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves npm dependencies with npm itself and captures the installed dependency tree. */
public class NpmDependencyResolver {
    private final ObjectMapper json = new ObjectMapper();
    private final String npm;

    public NpmDependencyResolver(String npm) {
        this.npm = npm;
    }

    public Result resolve(
            List<String> packageSpecs, String registry, Path npmCache, Path workingDirectory)
            throws Exception {
        Files.createDirectories(workingDirectory);
        Files.createDirectories(npmCache);
        Path manifest = workingDirectory.resolve("package.json");
        json.writeValue(
                manifest.toFile(),
                Map.of("name", "wstool-npm-import", "version", "1.0.0", "private", true));

        List<String> install =
                new ArrayList<>(
                        List.of(
                                npm,
                                "install",
                                "--ignore-scripts",
                                "--no-audit",
                                "--no-fund",
                                "--save-exact",
                                "--no-progress",
                                "--loglevel=error",
                                "--registry=" + registry,
                                "--cache=" + npmCache.toAbsolutePath()));
        install.addAll(packageSpecs);
        run(install, workingDirectory);

        String listing =
                run(
                        List.of(npm, "ls", "--json", "--all", "--long", "--offline", "--depth=999"),
                        workingDirectory);
        JsonNode tree = json.readTree(listing);
        LinkedHashMap<String, ResolvedPackage> packages = new LinkedHashMap<>();
        collect(tree.path("dependencies"), packages);
        if (packages.isEmpty()) {
            throw new IllegalStateException("npm resolved no packages for " + packageSpecs);
        }
        return new Result(Map.copyOf(packages), readRootDependencies(manifest));
    }

    private Map<String, String> readRootDependencies(Path manifest) throws IOException {
        JsonNode root = json.readTree(manifest.toFile()).path("dependencies");
        LinkedHashMap<String, String> dependencies = new LinkedHashMap<>();
        root.fields()
                .forEachRemaining(
                        field -> dependencies.put(field.getKey(), field.getValue().asText()));
        return Map.copyOf(dependencies);
    }

    private void collect(JsonNode dependencies, Map<String, ResolvedPackage> packages)
            throws IOException {
        var fields = dependencies.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode dependency = field.getValue();
            String name = dependency.path("name").asText(field.getKey());
            String version = dependency.path("version").asText();
            String pathValue = dependency.path("path").asText();
            if (version.isBlank() || pathValue.isBlank()) {
                continue;
            }
            Path packagePath = Path.of(pathValue).toAbsolutePath().normalize();
            LinkedHashMap<String, String> childDependencies = new LinkedHashMap<>();
            JsonNode children = dependency.path("dependencies");
            var childFields = children.fields();
            while (childFields.hasNext()) {
                var child = childFields.next();
                String childVersion = child.getValue().path("version").asText();
                if (!childVersion.isBlank()) {
                    childDependencies.put(child.getKey(), childVersion);
                }
            }
            ResolvedPackage resolved =
                    new ResolvedPackage(name, version, packagePath, Map.copyOf(childDependencies));
            packages.putIfAbsent(key(name, version), resolved);
            collect(children, packages);
        }
    }

    private String run(List<String> command, Path directory) throws Exception {
        Process process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        byte[] output;
        try (var stream = process.getInputStream()) {
            output = stream.readAllBytes();
        }
        int status = process.waitFor();
        String text = new String(output, java.nio.charset.StandardCharsets.UTF_8);
        if (status != 0) {
            throw new IllegalStateException(
                    "Command failed with status "
                            + status
                            + ": "
                            + String.join(" ", command)
                            + "\n"
                            + text);
        }
        return text;
    }

    private String key(String name, String version) {
        return name + Character.toString(0) + version;
    }

    public record ResolvedPackage(
            String name, String version, Path directory, Map<String, String> dependencies) {}

    public record Result(
            Map<String, ResolvedPackage> packages, Map<String, String> rootDependencies) {}
}
