package wstool.common.path;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.ArchiveSourceConfig;
import wstool.common.packages.ExternalSource;
import wstool.common.packages.FileSourceConfig;
import wstool.common.packages.PackageConfig;
import wstool.common.workspace.LocalWorkspace;

@Singleton
public class BuildSourcePreparer {
    private static final Logger LOGGER = LogManager.getLogger(BuildSourcePreparer.class);
    private final DownloadManager downloadManager;
    private final ArchiveExtractor archiveExtractor;

    @Inject
    public BuildSourcePreparer(DownloadManager downloadManager, ArchiveExtractor archiveExtractor) {
        this.downloadManager = downloadManager;
        this.archiveExtractor = archiveExtractor;
    }

    public Path prepare(LocalWorkspace workspace, PackageConfig pkg) throws Exception {
        PackageMajorVersion name = pkg.name();
        if (pkg.externalSources().isEmpty()) {
            return workspace.source(name);
        }

        Path packageSource = workspace.source(name);
        Path intermediate = workspace.buildSource(name);
        deleteTree(intermediate);
        Files.createDirectories(intermediate);

        List<URI> patches = new ArrayList<>();
        for (ExternalSource source : pkg.externalSources()) {
            if (source instanceof ArchiveSourceConfig archive) {
                Path cachedArchive =
                        downloadManager.get(
                                DownloadRequest.builder(
                                                workspace.cache().resolve("sources/downloads"),
                                                resolveSourceUri(packageSource, archive.uri()))
                                        .checksum(archive.checksum())
                                        .build());
                archiveExtractor.extract(cachedArchive, intermediate, archive.stripComponents());
            } else if (source instanceof FileSourceConfig file) {
                Path cached =
                        downloadManager.get(
                                DownloadRequest.builder(
                                                workspace.cache().resolve("sources/downloads"),
                                                resolveSourceUri(packageSource, file.uri()))
                                        .checksum(file.checksum())
                                        .build());
                Path destination = resolveInside(intermediate, file.path());
                copyFile(cached, destination);
            }
            patches.addAll(source.patches());
        }

        if (!Files.isDirectory(packageSource)) {
            throw new IOException("Package source directory does not exist: " + packageSource);
        }
        copyTree(packageSource, intermediate);

        for (URI patch : patches) {
            applyPatch(resolvePatch(workspace, packageSource, intermediate, patch), intermediate);
        }

        return intermediate;
    }

    private Path resolvePatch(
            LocalWorkspace workspace, Path packageSource, Path intermediate, URI patch)
            throws Exception {
        if (patch.getScheme() == null) {
            return resolveInside(intermediate, patch.toString());
        }
        if (patch.getScheme().equalsIgnoreCase("file")) {
            Path patchPath = Path.of(patch).toAbsolutePath().normalize();
            Path sourceRoot = packageSource.toAbsolutePath().normalize();
            if (patchPath.startsWith(sourceRoot)) {
                return intermediate.resolve(sourceRoot.relativize(patchPath));
            }
            return patchPath;
        }

        return downloadManager.get(
                DownloadRequest.builder(workspace.cache().resolve("sources/patches"), patch)
                        .build());
    }

    private URI resolveSourceUri(Path packageSource, URI uri) {
        if (uri.getScheme() == null) {
            return packageSource.resolve(uri.toString()).normalize().toUri();
        }
        return uri;
    }

    private void applyPatch(Path patch, Path workingDirectory) throws Exception {
        if (!Files.isRegularFile(patch)) {
            throw new IOException("Patch file does not exist: " + patch);
        }
        List<String> command =
                List.of(
                        "patch",
                        "--batch",
                        "--forward",
                        "-p1",
                        "-i",
                        patch.toAbsolutePath().toString());
        LOGGER.info("Applying patch {} in {}", patch, workingDirectory);
        Process process =
                new ProcessBuilder(command)
                        .directory(workingDirectory.toFile())
                        .inheritIO()
                        .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Patch command failed with exit code " + exitCode + ": " + patch);
        }
    }

    private Path resolveInside(Path root, String relativePath) throws IOException {
        Path relative = Path.of(relativePath).normalize();
        Path target = root.resolve(relative).normalize();
        if (relativePath.isBlank() || relative.isAbsolute() || !target.startsWith(root)) {
            throw new IOException(
                    "Path must stay within the build source directory: " + relativePath);
        }
        return target;
    }

    private void copyTree(Path source, Path destination) throws IOException {
        Path sourceRoot = source.toAbsolutePath().normalize();
        Path destinationRoot = destination.toAbsolutePath().normalize();
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attrs) throws IOException {
                        Path target = destination.resolve(source.relativize(directory));
                        removeConflictingTarget(target, true);
                        Files.createDirectories(target);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Path target = destination.resolve(source.relativize(file));
                        removeConflictingTarget(target, false);
                        Files.createDirectories(target.getParent());
                        if (attrs.isSymbolicLink()) {
                            Path linkTarget = Files.readSymbolicLink(file);
                            Path resolvedTarget =
                                    (linkTarget.isAbsolute()
                                                    ? linkTarget
                                                    : file.getParent().resolve(linkTarget))
                                            .normalize();
                            if (resolvedTarget.startsWith(sourceRoot)) {
                                Path copiedTarget =
                                        destinationRoot.resolve(
                                                sourceRoot.relativize(resolvedTarget));
                                linkTarget =
                                        target.toAbsolutePath()
                                                .getParent()
                                                .relativize(copiedTarget);
                            }
                            Files.createSymbolicLink(target, linkTarget);
                        } else {
                            Files.copy(
                                    file,
                                    target,
                                    StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.COPY_ATTRIBUTES,
                                    LinkOption.NOFOLLOW_LINKS);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private void copyFile(Path source, Path destination) throws IOException {
        removeConflictingTarget(destination, false);
        Files.createDirectories(destination.getParent());
        Files.copy(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void removeConflictingTarget(Path target, boolean targetIsDirectory)
            throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        boolean existingDirectory =
                Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(target);
        if (existingDirectory != targetIsDirectory) {
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
