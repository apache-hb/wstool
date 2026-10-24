package wstool.common.path;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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
    private final HttpClient httpClient;
    private final ArchiveExtractor archiveExtractor;

    @Inject
    public BuildSourcePreparer(HttpClient httpClient, ArchiveExtractor archiveExtractor) {
        this.httpClient = httpClient;
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
                        cachedFile(
                                workspace.cache(),
                                resolveSourceUri(packageSource, archive.uri()),
                                archive.checksum());
                archiveExtractor.extract(cachedArchive, intermediate, archive.stripComponents());
            } else if (source instanceof FileSourceConfig file) {
                Path cached =
                        cachedFile(
                                workspace.cache(),
                                resolveSourceUri(packageSource, file.uri()),
                                file.checksum());
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

    private Path cachedFile(Path workspaceCache, URI uri, String checksum) throws Exception {
        String normalizedChecksum = checksum.toLowerCase();

        Path downloads = workspaceCache.resolve("sources/downloads");
        Files.createDirectories(downloads);
        Path cached =
                downloads.resolve(sha256(normalizedChecksum.getBytes(StandardCharsets.UTF_8)));
        if (Files.isRegularFile(cached) && normalizedChecksum.equals(sha256(cached))) {
            return cached;
        }

        Path temporary = Files.createTempFile(downloads, ".download-", ".tmp");
        try {
            download(uri, temporary);
            String actualChecksum = sha256(temporary);
            if (!normalizedChecksum.equals(actualChecksum)) {
                throw new IOException(
                        "SHA-256 mismatch for "
                                + uri
                                + ": expected "
                                + checksum
                                + ", got "
                                + actualChecksum);
            }
            moveIntoPlace(temporary, cached);
        } catch (Exception e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        return cached;
    }

    private void download(URI uri, Path destination) throws Exception {
        if (uri.getScheme() == null || uri.getScheme().equalsIgnoreCase("file")) {
            Path source = uri.getScheme() == null ? Path.of(uri.toString()) : Path.of(uri);
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (!uri.getScheme().equalsIgnoreCase("http")
                && !uri.getScheme().equalsIgnoreCase("https")) {
            throw new IOException("Unsupported source URI scheme: " + uri);
        }

        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<Path> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(destination);
            throw new IOException("Could not download " + uri + ": HTTP " + response.statusCode());
        }
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

        String key = sha256(patch.toString().getBytes(StandardCharsets.UTF_8));
        Path patchCache = workspace.cache().resolve("sources/patches").resolve(key);
        if (!Files.isRegularFile(patchCache)) {
            Files.createDirectories(patchCache.getParent());
            Path temporary = Files.createTempFile(patchCache.getParent(), ".patch-", ".tmp");
            try {
                download(patch, temporary);
                moveIntoPlace(temporary, patchCache);
            } catch (Exception e) {
                Files.deleteIfExists(temporary);
                throw e;
            }
        }
        return patchCache;
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

    private String sha256(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
