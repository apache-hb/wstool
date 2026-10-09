package wstool.simple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import wstool.simple.archive.CreateArchiveInput;
import wstool.simple.archive.ExtractArchiveInput;

public class ArchiveHelper {
    private static final int PERMISSIONS_EXTRA_FIELD = 0x5754;

    public void extract(ExtractArchiveInput input) throws Exception {
        try (ZipFile zipFile = new ZipFile(input.archive().toFile())) {
            zipFile.stream()
                    .forEach(
                            entry -> {
                                try {
                                    Path entryDestination =
                                            input.destination().resolve(entry.getName());
                                    if (entry.isDirectory()) {
                                        Files.createDirectories(entryDestination);
                                    } else {
                                        Files.createDirectories(entryDestination.getParent());
                                        try (var entryStream = zipFile.getInputStream(entry)) {
                                            Files.copy(entryStream, entryDestination);
                                        }
                                    }
                                    restorePermissions(entry, entryDestination);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
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

    private void restorePermissions(ZipEntry entry, Path destination) throws IOException {
        byte[] extra = entry.getExtra();
        if (extra == null) {
            restoreBinExecutablePermission(entry, destination);
            return;
        }
        for (int index = 0; index + 6 <= extra.length; ) {
            int id = (extra[index] & 0xff) | ((extra[index + 1] & 0xff) << 8);
            int size = (extra[index + 2] & 0xff) | ((extra[index + 3] & 0xff) << 8);
            int end = index + 4 + size;
            if (end > extra.length) {
                return;
            }
            if (id == PERMISSIONS_EXTRA_FIELD && size == 2) {
                int mode = (extra[index + 4] & 0xff) | ((extra[index + 5] & 0xff) << 8);
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
                Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
                for (int bit = 0; bit < ordered.length; bit++) {
                    if ((mode & (1 << (8 - bit))) != 0) {
                        permissions.add(ordered[bit]);
                    }
                }
                try {
                    Files.setPosixFilePermissions(destination, permissions);
                } catch (UnsupportedOperationException ignored) {
                    // Keep platform-default permissions on filesystems without POSIX support.
                }
                return;
            }
            index = end;
        }
        restoreBinExecutablePermission(entry, destination);
    }

    private void restoreBinExecutablePermission(ZipEntry entry, Path destination)
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
}
