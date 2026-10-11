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
                                "--package-lock-only",
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

        JsonNode lockPackages =
                json.readTree(workingDirectory.resolve("package-lock.json").toFile())
                        .path("packages");
        LinkedHashMap<String, JsonNode> installedPackages = readInstalledPackages(lockPackages);
        LinkedHashMap<String, ResolvedPackage> packages = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : installedPackages.entrySet()) {
            String installPath = entry.getKey();
            JsonNode installed = entry.getValue();
            String name = installed.path("name").asText(packageName(installPath));
            String version = installed.path("version").asText();
            ArchiveSource archiveSource =
                    readArchiveSource(installed, name, version, registry, workingDirectory);
            LinkedHashMap<String, String> dependencies =
                    readDependencies(
                            installPath, installed.path("dependencies"), installedPackages);
            packages.putIfAbsent(
                    key(name, version),
                    new ResolvedPackage(
                            name,
                            version,
                            archiveSource.uri(),
                            archiveSource.integrity(),
                            Map.copyOf(dependencies)));
        }
        if (packages.isEmpty()) {
            throw new IllegalStateException("npm resolved no packages for " + packageSpecs);
        }
        return new Result(Map.copyOf(packages), readRootDependencies(manifest));
    }

    private LinkedHashMap<String, JsonNode> readInstalledPackages(JsonNode lockPackages) {
        LinkedHashMap<String, JsonNode> packages = new LinkedHashMap<>();
        var fields = lockPackages.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().isEmpty()) {
                continue;
            }
            JsonNode installed = field.getValue();
            String name = installed.path("name").asText(packageName(field.getKey()));
            String version = installed.path("version").asText();
            if (!name.isBlank() && !version.isBlank()) {
                packages.put(field.getKey(), installed);
            }
        }
        return packages;
    }

    private String packageName(String lockPath) {
        int marker = lockPath.lastIndexOf("node_modules/");
        return marker < 0 ? "" : lockPath.substring(marker + "node_modules/".length());
    }

    private Map<String, String> readRootDependencies(Path manifest) throws IOException {
        JsonNode root = json.readTree(manifest.toFile()).path("dependencies");
        LinkedHashMap<String, String> dependencies = new LinkedHashMap<>();
        root.fields()
                .forEachRemaining(
                        field -> dependencies.put(field.getKey(), field.getValue().asText()));
        return Map.copyOf(dependencies);
    }

    private LinkedHashMap<String, String> readDependencies(
            String installPath, JsonNode dependencySpecs, Map<String, JsonNode> installedPackages) {
        LinkedHashMap<String, String> dependencies = new LinkedHashMap<>();
        var fields = dependencySpecs.fields();
        while (fields.hasNext()) {
            var dependency = fields.next();
            String target = resolveInstallPath(installPath, dependency.getKey(), installedPackages);
            if (target != null) {
                dependencies.put(
                        dependency.getKey(),
                        installedPackages.get(target).path("version").asText());
            }
        }
        return dependencies;
    }

    private String resolveInstallPath(
            String packagePath, String dependencyName, Map<String, JsonNode> installedPackages) {
        String parent = packagePath;
        while (true) {
            String candidate =
                    (parent.isEmpty() ? "" : parent + "/") + "node_modules/" + dependencyName;
            if (installedPackages.containsKey(candidate)) {
                return candidate;
            }
            int marker = parent.lastIndexOf("/node_modules/");
            if (marker >= 0) {
                parent = parent.substring(0, marker);
            } else if (parent.startsWith("node_modules/")) {
                parent = "";
            } else {
                return null;
            }
        }
    }

    private ArchiveSource readArchiveSource(
            JsonNode installed, String name, String version, String registry, Path workingDirectory)
            throws Exception {
        String archive = installed.path("resolved").asText();
        String integrity = installed.path("integrity").asText();
        if (!archive.isBlank() && !integrity.isBlank()) {
            return new ArchiveSource(archive, integrity);
        }
        String metadata =
                run(
                        List.of(
                                npm,
                                "view",
                                name + "@" + version,
                                "dist",
                                "--json",
                                "--registry=" + registry,
                                "--loglevel=error"),
                        workingDirectory);
        JsonNode distribution = json.readTree(metadata);
        archive = distribution.path("tarball").asText();
        integrity = distribution.path("integrity").asText();
        if (archive.isBlank() || integrity.isBlank()) {
            throw new IllegalStateException(
                    "npm did not provide a tarball URL and integrity for " + name + "@" + version);
        }
        return new ArchiveSource(archive, integrity);
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

    public record ArchiveSource(String uri, String integrity) {}

    public record ResolvedPackage(
            String name,
            String version,
            String archiveUri,
            String integrity,
            Map<String, String> dependencies) {}

    public record Result(
            Map<String, ResolvedPackage> packages, Map<String, String> rootDependencies) {}
}
