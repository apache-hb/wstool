package wstool.simple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import wstool.simple.archive.CreateArchiveInput;
import wstool.simple.archive.ExtractArchiveInput;

public class ArchiveHelper {
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
                                        Files.copy(zipFile.getInputStream(entry), entryDestination);
                                    }
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
                                            zipOutputStream.putNextEntry(
                                                    new ZipEntry(entryName + "/"));
                                            zipOutputStream.closeEntry();
                                        }
                                    } else {
                                        zipOutputStream.putNextEntry(new ZipEntry(entryName));
                                        Files.copy(path, zipOutputStream);
                                        zipOutputStream.closeEntry();
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
        }
    }
}
