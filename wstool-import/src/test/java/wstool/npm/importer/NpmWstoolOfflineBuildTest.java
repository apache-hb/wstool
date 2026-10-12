package wstool.npm.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpmWstoolOfflineBuildTest {
    @TempDir Path tempDir;

    @Test
    void installsImportedPackagesAndBuildsProjectFromLocalSysrootOnly() throws Exception {
        Path importedPackage =
                resourceDirectory(
                        "npm-wstool-build/sysroot/share/wstool-npm/packages/npm_@scope/is-number/7.x");
        Path importedPrefix = tempDir.resolve("imported-prefix");
        runTool(importedPackage, importedPrefix, tempDir.resolve("imported-private"));
        Path installedPackage =
                importedPrefix.resolve(
                        "share/wstool-npm/packages/npm_@scope/is-number/7.x/package/index.js");
        assertTrue(Files.isRegularFile(installedPackage));

        Path project = tempDir.resolve("project");
        copyTree(resourceDirectory("npm-wstool-build/project"), project);
        Path prefix = tempDir.resolve("application-prefix");
        Path privateDirectory = tempDir.resolve("application-private");
        runTool(project, prefix, privateDirectory);

        Path builtProject = prefix.resolve("lib/node_modules/offline-npm-fixture");
        assertEquals("true", Files.readString(builtProject.resolve("dist/result.txt")));
        assertTrue(
                Files.isRegularFile(
                        builtProject.resolve("node_modules/@scope/is-number/index.js")));
    }

    private void runTool(Path source, Path prefix, Path privateDirectory) throws Exception {
        Path tool = tempDir.resolve("npm-wstool");
        try (var input = getClass().getResourceAsStream("/npm-wstool")) {
            if (input == null) throw new IllegalStateException("npm-wstool resource is missing");
            Files.copy(input, tool, StandardCopyOption.REPLACE_EXISTING);
        }
        tool.toFile().setExecutable(true);
        Files.createDirectories(prefix);
        Files.createDirectories(privateDirectory);
        ProcessBuilder builder = new ProcessBuilder(tool.toString(), "release");
        builder.environment().put("WST_SRCDIR", source.toAbsolutePath().toString());
        builder.environment().put("WST_PREFIX", prefix.toAbsolutePath().toString());
        builder.environment().put("WST_PRIVATE", privateDirectory.toAbsolutePath().toString());
        builder.environment().put("npm_config_offline", "true");
        builder.environment().put("npm_config_registry", "http://127.0.0.1:9/");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int status = process.waitFor();
        assertEquals(0, status, output);
    }

    private Path resourceDirectory(String resource) throws Exception {
        return Path.of(getClass().getResource("/" + resource).toURI());
    }

    private void copyTree(Path source, Path destination) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
