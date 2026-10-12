package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.Distribution.Archive;
import wstool.Distribution.Folder;
import wstool.simple.archive.ImmutableCreateArchiveInput;

class PackageSourceHelperTest {
    @TempDir Path tempDir;

    @Test
    void materializesNestedPackageSourceFromChecksummedBundle() throws Exception {
        Path bundleContents = tempDir.resolve("bundle-contents");
        Path packageDirectory = bundleContents.resolve("packages/example/1.0");
        Files.createDirectories(packageDirectory.resolve("wstool/bin"));
        copyResource(
                "package-source-helper/bundle-contents/packages/example/1.0/wstool.pkl",
                packageDirectory.resolve("wstool.pkl"));
        Path tool = packageDirectory.resolve("wstool/bin/example-tool");
        copyResource(
                "package-source-helper/bundle-contents/packages/example/1.0/wstool/bin/example-tool",
                tool);
        tool.toFile().setExecutable(true, false);

        Path archive = tempDir.resolve("maven-packages.zip");
        new ArchiveHelper()
                .create(
                        ImmutableCreateArchiveInput.builder()
                                .source(bundleContents)
                                .destination(archive)
                                .build());
        Archive parent = new Archive("maven-import", "1.0.0", archive.toUri(), sha256(archive));
        Folder source = new Folder("example", "1.0", parent, "packages/example/1.0");

        Path resolved = packageSourceHelper().materialize(source, tempDir.resolve("workspace"));

        assertEquals(
                "fixture = \"package config\"\n", Files.readString(resolved.resolve("wstool.pkl")));
        assertTrue(Files.isExecutable(resolved.resolve("wstool/bin/example-tool")));
    }

    @Test
    void rejectsNestedSourcePathsOutsideTheirBundle() throws Exception {
        Path archive = tempDir.resolve("bundle.zip");
        Path contents = tempDir.resolve("contents");
        Files.createDirectories(contents);
        new ArchiveHelper()
                .create(
                        ImmutableCreateArchiveInput.builder()
                                .source(contents)
                                .destination(archive)
                                .build());
        Archive parent = new Archive("bundle", "1", archive.toUri(), sha256(archive));
        Folder source = new Folder("bad", "1", parent, "../outside");

        assertThrows(
                java.io.IOException.class,
                () -> packageSourceHelper().materialize(source, tempDir.resolve("workspace")));
    }

    private String sha256(Path file) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private PackageSourceHelper packageSourceHelper() {
        return new PackageSourceHelper(
                new DistributionArchiveHelper(new ArchiveHelper()),
                new GitHelper(false),
                new PackageMapper());
    }

    private void copyResource(String name, Path destination) throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(name);
        if (resource == null) {
            throw new IllegalArgumentException("Missing test resource: " + name);
        }
        try (resource) {
            Files.copy(resource, destination);
        }
    }
}
