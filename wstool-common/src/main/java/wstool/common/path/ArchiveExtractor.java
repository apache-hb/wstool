package wstool.common.path;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

@Singleton
public class ArchiveExtractor {
    private final ArchiveStreamFactory archiveStreamFactory;

    @Inject
    public ArchiveExtractor(ArchiveStreamFactory archiveStreamFactory) {
        this.archiveStreamFactory = archiveStreamFactory;
    }

    public void extract(Path archive, Path destination, int stripComponents)
            throws IOException, ArchiveException {
        Files.createDirectories(destination);
        List<Map.Entry<Path, Path>> hardLinks = new ArrayList<>();
        try (InputStream compressed = decompressedStream(archive);
                ArchiveInputStream<?> input =
                        archiveStreamFactory.createArchiveInputStream(
                                new BufferedInputStream(compressed))) {
            ArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path relative = strippedPath(entry.getName(), stripComponents);
                if (relative == null) {
                    continue;
                }
                Path target = destination.resolve(relative).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException(
                            "Archive entry escapes extraction directory: " + entry.getName());
                }

                if (entry instanceof TarArchiveEntry tarEntry && tarEntry.isSymbolicLink()) {
                    prepareTarget(target, false);
                    createSymbolicLink(target, tarEntry.getLinkName(), destination);
                } else if (entry instanceof TarArchiveEntry tarEntry && tarEntry.isLink()) {
                    prepareTarget(target, false);
                    Path linkTarget = strippedPath(tarEntry.getLinkName(), stripComponents);
                    if (linkTarget != null) {
                        hardLinks.add(
                                Map.entry(target, destination.resolve(linkTarget).normalize()));
                    }
                } else if (entry.isDirectory()) {
                    prepareTarget(target, true);
                    Files.createDirectories(target);
                } else {
                    prepareTarget(target, false);
                    Files.createDirectories(target.getParent());
                    try (var output = Files.newOutputStream(target)) {
                        input.transferTo(output);
                    }
                    if (entry instanceof TarArchiveEntry tarEntry
                            && (tarEntry.getMode() & 0111) != 0
                            && !target.toFile().setExecutable(true, false)) {
                        throw new IOException("Could not preserve executable mode for " + target);
                    }
                }
            }
        }

        for (Map.Entry<Path, Path> hardLink : hardLinks) {
            if (!hardLink.getValue().startsWith(destination)
                    || !Files.isRegularFile(hardLink.getValue())) {
                throw new IOException("Invalid archive hard link target: " + hardLink.getValue());
            }
            Files.createDirectories(hardLink.getKey().getParent());
            Files.copy(hardLink.getValue(), hardLink.getKey(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private InputStream decompressedStream(Path archive) throws IOException {
        BufferedInputStream input = new BufferedInputStream(Files.newInputStream(archive));
        input.mark(8192);
        try {
            return new CompressorStreamFactory().createCompressorInputStream(input);
        } catch (CompressorException ignored) {
            input.reset();
            return input;
        }
    }

    private Path strippedPath(String name, int stripComponents) throws IOException {
        String normalizedName = name.replace('\\', '/');
        Path path = Path.of(normalizedName).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IOException("Invalid archive entry path: " + name);
        }
        if (path.toString().isEmpty() || path.getNameCount() <= stripComponents) {
            return null;
        }
        Path stripped = path.subpath(stripComponents, path.getNameCount());
        if (stripped.startsWith("..")) {
            throw new IOException("Invalid archive entry path: " + name);
        }
        return stripped;
    }

    private void createSymbolicLink(Path link, String name, Path root) throws IOException {
        Path linkTarget = Path.of(name);
        if (linkTarget.isAbsolute()
                || !link.getParent().resolve(linkTarget).normalize().startsWith(root)) {
            throw new IOException("Archive symbolic link escapes extraction directory: " + name);
        }
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, linkTarget);
    }

    private void prepareTarget(Path target, boolean directory) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        boolean existingDirectory =
                Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(target);
        if (!directory || !existingDirectory) {
            deleteTree(target);
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException error)
                            throws IOException {
                        if (error != null) {
                            throw error;
                        }
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
