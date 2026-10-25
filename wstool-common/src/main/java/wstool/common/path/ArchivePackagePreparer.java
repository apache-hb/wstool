package wstool.common.path;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.io.file.PathUtils;

/** Extracts a distribution archive into private storage and links it into the workspace sources. */
@Singleton
public class ArchivePackagePreparer {
    private final ArchiveCache archiveCache;
    private final ArchiveExtractor archiveExtractor;

    @Inject
    public ArchivePackagePreparer(ArchiveCache archiveCache, ArchiveExtractor archiveExtractor) {
        this.archiveCache = archiveCache;
        this.archiveExtractor = archiveExtractor;
    }

    public void prepare(
            Path cache, Path privateSource, Path sourceLink, URI archive, String checksum)
            throws Exception {
        Path cachedArchive = archiveCache.get(cache, archive, checksum);
        Path extractionMarker =
                privateSource.resolveSibling("." + privateSource.getFileName() + ".sha256");
        if (!isCurrentExtraction(privateSource, extractionMarker, checksum)) {
            extract(cachedArchive, privateSource, extractionMarker, checksum);
        }
        linkSource(sourceLink, privateSource);
    }

    public void remove(Path privateSource, Path sourceLink) throws IOException {
        if (Files.isSymbolicLink(sourceLink)) {
            Files.delete(sourceLink);
        }
        if (Files.exists(privateSource, LinkOption.NOFOLLOW_LINKS)) {
            PathUtils.deleteDirectory(privateSource);
        }
        Path marker = privateSource.resolveSibling("." + privateSource.getFileName() + ".sha256");
        Files.deleteIfExists(marker);
    }

    private boolean isCurrentExtraction(Path source, Path marker, String checksum)
            throws IOException {
        return Files.isDirectory(source)
                && Files.isRegularFile(source.resolve("wstool.pkl"))
                && Files.isRegularFile(marker)
                && Files.readString(marker, StandardCharsets.UTF_8)
                        .strip()
                        .equalsIgnoreCase(checksum);
    }

    private void extract(Path archive, Path destination, Path marker, String checksum)
            throws Exception {
        Files.createDirectories(destination.getParent());
        Path temporary =
                destination.resolveSibling(
                        "." + destination.getFileName() + "-" + UUID.randomUUID());
        try {
            archiveExtractor.extract(archive, temporary, 0);
            if (!Files.isRegularFile(temporary.resolve("wstool.pkl"))) {
                throw new IOException(
                        "Distribution archive does not contain a root wstool.pkl: " + archive);
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                PathUtils.deleteDirectory(destination);
            }
            moveIntoPlace(temporary, destination);
            Files.writeString(marker, checksum, StandardCharsets.UTF_8);
        } finally {
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                PathUtils.deleteDirectory(temporary);
            }
        }
    }

    private void linkSource(Path sourceLink, Path privateSource) throws IOException {
        Files.createDirectories(sourceLink.getParent());
        Path target = privateSource.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(sourceLink)) {
            Path current = Files.readSymbolicLink(sourceLink);
            if (!current.isAbsolute()) {
                current = sourceLink.getParent().resolve(current).normalize();
            }
            if (current.equals(target)) {
                return;
            }
            Files.delete(sourceLink);
        } else if (Files.exists(sourceLink, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Cannot link archive package over an existing source path: " + sourceLink);
        }
        Files.createSymbolicLink(sourceLink, target);
    }

    private void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(source, destination);
        }
    }
}
