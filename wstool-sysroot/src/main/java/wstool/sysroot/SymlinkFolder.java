package wstool.sysroot;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;

/** Materializes a package closure as a merged directory of symlinks. */
public final class SymlinkFolder {
    private SymlinkFolder() {}

    public static void create(
            LocalWorkspace workspace, Set<PackageMajorVersion> packages, Path target)
            throws Exception {
        Files.createDirectories(target);
        for (PackageMajorVersion name : packages) {
            Path source = workspace.prefix(name);
            if (!Files.isDirectory(source)) {
                continue;
            }
            Files.walkFileTree(
                    source,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) throws IOException {
                            Files.createDirectories(target.resolve(source.relativize(dir)));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            Path link = target.resolve(source.relativize(file));
                            try {
                                Files.createSymbolicLink(link, file);
                            } catch (FileAlreadyExistsException ignored) {
                                // Existing entries keep their original owner.
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
    }
}
