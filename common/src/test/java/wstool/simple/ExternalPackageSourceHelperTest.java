package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.Package.ExternalArchive;
import wstool.simple.archive.ImmutableCreateArchiveInput;

class ExternalPackageSourceHelperTest {
    @TempDir Path tempDir;

    @Test
    void refreshesExternalArchiveCacheWhenItsSourceRootChanges() throws Exception {
        Path contents = tempDir.resolve("archive-contents");
        Path packageDirectory = contents.resolve("package");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("package.json"), "{}\n");

        Path archive = tempDir.resolve("npm-package.zip");
        new ArchiveHelper()
                .create(
                        ImmutableCreateArchiveInput.builder()
                                .source(contents)
                                .destination(archive)
                                .build());
        String checksum =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(archive)));
        GitHelper git = new GitHelper(false);
        DistributionArchiveHelper archives = new DistributionArchiveHelper(new ArchiveHelper());
        PackageSourceHelper sources = new PackageSourceHelper(archives, git, new PackageMapper());
        ExternalPackageSourceHelper helper =
                new ExternalPackageSourceHelper(archives, sources, new ExternalFilesHelper(), git);
        Path workspace = tempDir.resolve("workspace");

        Path unstripped =
                helper.materialize(
                        new ExternalArchive(archive.toUri(), List.of(), checksum, null),
                        "npm_example",
                        "1.x",
                        workspace);
        assertTrue(Files.isRegularFile(unstripped.resolve("package/package.json")));

        Path stripped =
                helper.materialize(
                        new ExternalArchive(archive.toUri(), List.of(), checksum, "package"),
                        "npm_example",
                        "1.x",
                        workspace);
        assertTrue(Files.isRegularFile(stripped.resolve("package.json")));
        assertFalse(Files.exists(stripped.resolve("package/package.json")));
    }
}
