package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.simple.archive.ImmutableExtractArchiveInput;

class ArchiveHelperTest {
    @TempDir Path tempDir;

    @Test
    void extractsGzipCompressedTarAndPreservesPermissions() throws Exception {
        Path archive = tempDir.resolve("source.tar.gz");
        byte[] contents = "#!/bin/sh\necho hello\n".getBytes();
        try (OutputStream file = Files.newOutputStream(archive);
                GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(file);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry("package/bin/tool");
            entry.setSize(contents.length);
            entry.setMode(0755);
            tar.putArchiveEntry(entry);
            tar.write(contents);
            tar.closeArchiveEntry();
            tar.finish();
        }

        Path destination = tempDir.resolve("extracted");
        new ArchiveHelper()
                .extract(
                        ImmutableExtractArchiveInput.builder()
                                .archive(archive)
                                .destination(destination)
                                .build());

        Path tool = destination.resolve("package/bin/tool");
        assertEquals("#!/bin/sh\necho hello\n", Files.readString(tool));
        assertEquals(
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE),
                Files.getPosixFilePermissions(tool));
    }
}
