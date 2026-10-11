package wstool.simple;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import wstool.simple.archive.CreateArchiveInput;
import wstool.simple.archive.ExtractArchiveInput;

public class ArchiveHelper {
    private static final int PERMISSIONS_EXTRA_FIELD = 0x5754;
    private static final int DATA_BUFFER_SIZE = 64 * 1024;

    public void extract(ExtractArchiveInput input) throws Exception {
        Path root = input.destination().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Map<Path, Integer> directoryPermissions = new LinkedHashMap<>();
        Map<Path, Path> hardLinks = new LinkedHashMap<>();
        boolean zipArchive;

        try (InputStream file = Files.newInputStream(input.archive());
                BufferedInputStream buffered = new BufferedInputStream(file)) {
            InputStream decompressed = new BufferedInputStream(decompress(buffered));
            try (ArchiveInputStream<?> archive =
                    new ArchiveStreamFactory().createArchiveInputStream(decompressed)) {
                zipArchive = archive instanceof ZipArchiveInputStream;
                ArchiveEntry entry;
                while ((entry = archive.getNextEntry()) != null) {
                    if (!archive.canReadEntryData(entry)) {
                        throw new IOException("Unable to read archive entry: " + entry.getName());
                    }
                    Path path = resolveEntryPath(root, entry.getName());
                    if (entry.isDirectory()) {
                        rejectSymlinkParents(root, path);
                        Files.createDirectories(path);
                        directoryPermissions.put(path, entryPermissions(entry, true));
                    } else if (entry instanceof TarArchiveEntry tarEntry
                            && tarEntry.isSymbolicLink()) {
                        createSymbolicLink(root, path, tarEntry.getLinkName());
                    } else if (entry instanceof ZipArchiveEntry zipEntry
                            && zipEntry.isUnixSymlink()) {
                        createSymbolicLink(
                                root, path, new String(readEntry(archive), StandardCharsets.UTF_8));
                    } else if (entry instanceof TarArchiveEntry tarEntry && tarEntry.isLink()) {
                        Path target = resolveEntryPath(root, tarEntry.getLinkName());
                        hardLinks.put(path, target);
                    } else if (entry instanceof TarArchiveEntry tarEntry && !tarEntry.isFile()) {
                        throw new IOException(
                                "Unsupported archive entry type for " + entry.getName());
                    } else {
                        extractFile(archive, root, path, entryPermissions(entry, false));
                    }
                }
            }
        }

        for (Map.Entry<Path, Path> link : hardLinks.entrySet()) {
            createHardLink(root, link.getKey(), link.getValue());
        }
        List<Map.Entry<Path, Integer>> directories =
                directoryPermissions.entrySet().stream()
                        .sorted(
                                (left, right) ->
                                        Integer.compare(
                                                right.getKey().getNameCount(),
                                                left.getKey().getNameCount()))
                        .toList();
        for (Map.Entry<Path, Integer> directory : directories) {
            setPermissions(directory.getKey(), directory.getValue());
        }
        if (zipArchive) {
            restoreZipPermissions(input.archive(), root);
        }
    }

    private InputStream decompress(InputStream input) throws IOException {
        try {
            return new CompressorStreamFactory().createCompressorInputStream(input);
        } catch (CompressorException ignored) {
            return input;
        }
    }

    private void extractFile(ArchiveInputStream<?> archive, Path root, Path path, int permissions)
            throws IOException {
        rejectSymlinkParents(root, path);
        Files.createDirectories(path.getParent());
        try (OutputStream output = Files.newOutputStream(path)) {
            byte[] buffer = new byte[DATA_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = archive.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        }
        setPermissions(path, permissions);
    }

    private byte[] readEntry(ArchiveInputStream<?> archive) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[DATA_BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = archive.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        return output.toByteArray();
    }

    private void createSymbolicLink(Path root, Path link, String targetValue) throws IOException {
        if (targetValue == null || targetValue.isBlank()) {
            throw new IOException("Archive contains a symbolic link with no target: " + link);
        }
        rejectSymlinkParents(root, link);
        Files.createDirectories(link.getParent());
        Path target = Path.of(targetValue);
        if (target.isAbsolute() || !link.getParent().resolve(target).normalize().startsWith(root)) {
            throw new IOException("Archive symbolic link escapes destination: " + link);
        }
        Files.createSymbolicLink(link, target);
    }

    private void createHardLink(Path root, Path link, Path target) throws IOException {
        rejectSymlinkParents(root, link);
        rejectSymlinkParents(root, target);
        Files.createDirectories(link.getParent());
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Archive hard link target is not an extracted file: " + target);
        }
        Files.createLink(link, target);
    }

    private Path resolveEntryPath(Path root, String entryName) throws IOException {
        if (entryName == null || entryName.isBlank()) {
            throw new IOException("Archive contains an entry with no path");
        }
        Path path = root.resolve(entryName).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IOException("Archive entry escapes destination: " + entryName);
        }
        return path;
    }

    private void rejectSymlinkParents(Path root, Path path) throws IOException {
        Path parent = path.getParent();
        while (parent != null && parent.startsWith(root)) {
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("Archive entry traverses a symbolic link: " + path);
            }
            parent = parent.getParent();
        }
    }

    private int entryPermissions(ArchiveEntry entry, boolean directory) {
        if (entry instanceof TarArchiveEntry tarEntry) {
            return tarEntry.getMode() & 07777;
        }
        if (entry instanceof ZipArchiveEntry zipEntry) {
            int permissions = zipEntry.getUnixMode() & 07777;
            if (permissions != 0) {
                return permissions;
            }
        }
        return directory ? 0755 : 0644;
    }

    private void setPermissions(Path path, int mode) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            PosixFilePermission[] ordered = {
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
            };
            for (int index = 0; index < ordered.length; index++) {
                if ((mode & (1 << (8 - index))) != 0) {
                    permissions.add(ordered[index]);
                }
            }
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            path.toFile().setExecutable((mode & 0111) != 0, false);
        }
    }

    private void restoreZipPermissions(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        try (ZipFile zipFile = new ZipFile(archive)) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                Path path = root.resolve(entry.getName()).normalize();
                if (path.startsWith(root) && !Files.isSymbolicLink(path) && Files.exists(path)) {
                    restorePermissions(entry, path);
                }
            }
        }
    }

    private void restorePermissions(ZipArchiveEntry entry, Path destination) throws IOException {
        byte[] extra = entry.getExtra();
        if (extra != null) {
            for (int index = 0; index + 6 <= extra.length; ) {
                int id = (extra[index] & 0xff) | ((extra[index + 1] & 0xff) << 8);
                int size = (extra[index + 2] & 0xff) | ((extra[index + 3] & 0xff) << 8);
                int end = index + 4 + size;
                if (end > extra.length) {
                    return;
                }
                if (id == PERMISSIONS_EXTRA_FIELD && size == 2) {
                    int mode = (extra[index + 4] & 0xff) | ((extra[index + 5] & 0xff) << 8);
                    setPermissions(destination, mode);
                    return;
                }
                index = end;
            }
        }
        restoreBinExecutablePermission(entry, destination);
    }

    private void restoreBinExecutablePermission(ZipArchiveEntry entry, Path destination)
            throws IOException {
        if (entry.isDirectory() || destination.getParent() == null) {
            return;
        }
        if (!"bin".equals(destination.getParent().getFileName().toString())) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            permissions.addAll(Files.getPosixFilePermissions(destination));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(destination, permissions);
        } catch (UnsupportedOperationException ignored) {
            destination.toFile().setExecutable(true, false);
        }
    }

    public void create(CreateArchiveInput input) throws Exception {
        try (ZipOutputStream zipOutputStream =
                new ZipOutputStream(Files.newOutputStream(input.destination()))) {
            Files.walk(input.source())
                    .forEach(
                            path -> {
                                try {
                                    String entryName = input.source().relativize(path).toString();
                                    if (Files.isDirectory(path)) {
                                        if (!entryName.isEmpty()) {
                                            ZipEntry zipEntry = new ZipEntry(entryName + "/");
                                            zipEntry.setExtra(permissionsExtra(path));
                                            zipOutputStream.putNextEntry(zipEntry);
                                            zipOutputStream.closeEntry();
                                        }
                                    } else {
                                        ZipEntry zipEntry = new ZipEntry(entryName);
                                        zipEntry.setExtra(permissionsExtra(path));
                                        zipOutputStream.putNextEntry(zipEntry);
                                        Files.copy(path, zipOutputStream);
                                        zipOutputStream.closeEntry();
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
        }
    }

    private byte[] permissionsExtra(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            int mode = 0;
            PosixFilePermission[] ordered = {
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
            };
            for (int i = 0; i < ordered.length; i++) {
                if (permissions.contains(ordered[i])) {
                    mode |= 1 << (8 - i);
                }
            }
            return new byte[] {
                (byte) (PERMISSIONS_EXTRA_FIELD & 0xff),
                (byte) ((PERMISSIONS_EXTRA_FIELD >>> 8) & 0xff),
                2,
                0,
                (byte) (mode & 0xff),
                (byte) ((mode >>> 8) & 0xff)
            };
        } catch (UnsupportedOperationException ignored) {
            return new byte[0];
        }
    }
}
