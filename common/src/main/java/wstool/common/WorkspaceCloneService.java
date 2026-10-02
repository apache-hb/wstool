package wstool.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import wstool.Distribution;
import wstool.common.model.PackageDescriptor;

/** Clones or imports packages into a workspace. */
public final class WorkspaceCloneService {
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    public void clonePackages(WorkspaceLayout layout, List<String> packages, String from) {
        for (var packageName : packages) clonePackage(layout, packageName, packages, from);
    }

    public void cloneRepository(String source, String branch, Path target) {
        gitClone(source, branch, target);
    }

    private void clonePackage(
            WorkspaceLayout layout, String packageName, List<String> packages, String from) {
        var targetName = packageName;
        var targetPath = layout.sources().resolve(targetName);
        if (from == null) {
            var source = packageSource(layout, packageName);
            switch (source) {
                case Distribution.SourceCode sourceCode ->
                        cloneLocal(
                                layout.distribution()
                                        .worldFile()
                                        .getParent()
                                        .resolve(sourceCode.getPath())
                                        .toAbsolutePath()
                                        .normalize(),
                                targetPath);
                case Distribution.GitRepository git -> {
                    gitClone(git.getRepo().toString(), git.getBranch(), targetPath);
                    var descriptor = PackageDescriptorReader.read(targetPath);
                    if (descriptor.importOptions() != null)
                        importPackage(targetPath, targetPath, descriptor);
                }
                case Distribution.Archive archive -> importArchive(targetPath, archive);
                default ->
                        throw new IllegalArgumentException("Unsupported package source " + source);
            }
        } else if (isUrl(from)) {
            if (packages.size() != 1)
                throw new IllegalArgumentException("A git URL must be cloned separately");
            targetName = repositoryName(from);
            targetPath = layout.sources().resolve(targetName);
            gitClone(from, null, targetPath);
        } else {
            cloneLocal(Path.of(from).toAbsolutePath().normalize(), targetPath);
        }
        var descriptor = PackageDescriptorReader.read(targetPath);
        layout.ensureBuildDirectoryLink(targetPath, descriptor);
    }

    private static Distribution.PackageSource packageSource(
            WorkspaceLayout layout, String packageName) {
        var packages = layout.distribution().packages();
        if (packages.containsKey(packageName)) return packages.get(packageName);
        var matches =
                packages.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(packageName + "@"))
                        .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "Distribution does not contain one unambiguous package named " + packageName);
        }
        return matches.getFirst().getValue();
    }

    private static void cloneLocal(Path sourcePath, Path targetPath) {
        var descriptor = PackageDescriptorReader.read(sourcePath);
        if (descriptor.importOptions() == null) copy(sourcePath, targetPath);
        else importPackage(sourcePath, targetPath, descriptor);
    }

    private static boolean isUrl(String value) {
        return value.startsWith("https://")
                || value.startsWith("http://")
                || value.startsWith("git@");
    }

    private static String repositoryName(String url) {
        var value = url.substring(url.lastIndexOf('/') + 1);
        if (value.endsWith(".git")) value = value.substring(0, value.length() - 4);
        if (value.isBlank())
            throw new IllegalArgumentException("Cannot determine repository name from " + url);
        return value;
    }

    private static void gitClone(String source, String branch, Path target) {
        var command = new ArrayList<String>(List.of("git", "clone", "--depth", "1"));
        if (branch != null && !branch.isBlank()) command.addAll(List.of("--branch", branch));
        command.addAll(List.of(source, target.toString()));
        runGit(command);
    }

    private static void runGit(List<String> command) {
        try {
            var process = new ProcessBuilder(command).inheritIO().start();
            if (process.waitFor() != 0)
                throw new IllegalStateException("Git command failed: " + String.join(" ", command));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to run git", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command interrupted", e);
        }
    }

    private static void importPackage(
            Path descriptorSource, Path target, PackageDescriptor descriptor) {
        var importOptions = descriptor.importOptions();
        var temporary = createTemporaryDirectory(target);
        try {
            var archive = temporary.resolve("source" + archiveSuffix(importOptions.archive()));
            var extracted = temporary.resolve("source");
            download(importOptions.archive(), archive, importOptions.checksum());
            extract(archive, extracted);
            copy(sourceRoot(extracted), target);
            copyDescriptor(descriptorSource, target);
        } finally {
            delete(temporary);
        }
    }

    private static void importArchive(Path target, Distribution.Archive source) {
        var temporary = createTemporaryDirectory(target);
        try {
            var archive = temporary.resolve("source" + archiveSuffix(source.getUri()));
            var extracted = temporary.resolve("extracted");
            download(source.getUri(), archive, source.getChecksum());
            extract(archive, extracted);
            copy(sourceRoot(extracted), target);
        } finally {
            delete(temporary);
        }
    }

    private static String archiveSuffix(URI archive) {
        var path = archive.getPath().toLowerCase(Locale.ROOT);
        for (var suffix : List.of(".tar.gz", ".tgz", ".tar.xz", ".txz", ".zip")) {
            if (path.endsWith(suffix)) return suffix;
        }
        return ".archive";
    }

    private static void download(URI archive, Path target, String expectedChecksum) {
        if ("file".equalsIgnoreCase(archive.getScheme())) {
            try {
                Files.copy(Path.of(archive), target, StandardCopyOption.REPLACE_EXISTING);
                verifyChecksum(target, expectedChecksum);
                return;
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to copy archive " + archive, e);
            }
        }

        var cacheSetting = System.getenv("WSTOOL_DOWNLOAD_CACHE");
        if (cacheSetting == null || cacheSetting.isBlank()) {
            downloadRemote(archive, target);
            verifyChecksum(target, expectedChecksum);
            return;
        }

        var cacheDirectory = Path.of(cacheSetting).toAbsolutePath().normalize();
        var cachePath = cacheDirectory.resolve(cacheKey(archive, expectedChecksum));
        try {
            Files.createDirectories(cacheDirectory);
            if (Files.isRegularFile(cachePath)) {
                Files.copy(cachePath, target, StandardCopyOption.REPLACE_EXISTING);
                try {
                    verifyChecksum(target, expectedChecksum);
                    return;
                } catch (IllegalArgumentException invalidCache) {
                    Files.deleteIfExists(cachePath);
                    Files.deleteIfExists(target);
                }
            }
            downloadRemote(archive, target);
            verifyChecksum(target, expectedChecksum);
            var temporaryCacheFile =
                    Files.createTempFile(cacheDirectory, cachePath.getFileName().toString(), ".tmp");
            try {
                Files.copy(target, temporaryCacheFile, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(
                            temporaryCacheFile,
                            cachePath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(
                            temporaryCacheFile,
                            cachePath,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporaryCacheFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to cache downloaded archive " + archive, e);
        }
    }

    private static String cacheKey(URI archive, String expectedChecksum) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(archive.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (expectedChecksum != null)
                digest.update(expectedChecksum.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static void downloadRemote(URI archive, Path target) {
        if (!"http".equalsIgnoreCase(archive.getScheme())
                && !"https".equalsIgnoreCase(archive.getScheme())) {
            throw new IllegalArgumentException("Unsupported archive URI: " + archive);
        }
        IOException lastFailure = null;
        for (var candidate : downloadCandidates(archive)) {
            var request =
                    HttpRequest.newBuilder(candidate)
                            .timeout(Duration.ofMinutes(30))
                            .GET()
                            .build();
            try {
                var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(target));
                if (response.statusCode() >= 200 && response.statusCode() < 300) return;
                var failure =
                        new IOException(
                                "HTTP " + response.statusCode() + " downloading " + candidate);
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                lastFailure = failure;
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                lastFailure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Archive download interrupted", e);
            }
        }
        throw new UncheckedIOException("Unable to download archive " + archive, lastFailure);
    }

    private static List<URI> downloadCandidates(URI archive) {
        var candidates = new ArrayList<URI>();
        candidates.add(archive);
        if ("ftp.gnu.org".equalsIgnoreCase(archive.getHost())) {
            var mirror = URI.create("https://mirrors.kernel.org" + archive.getRawPath());
            if (!mirror.equals(archive)) candidates.add(mirror);
        }
        return candidates;
    }

    private static void verifyChecksum(Path archive, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) return;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(archive)) {
                var buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            var actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedChecksum)) {
                throw new IllegalArgumentException(
                        "Archive checksum mismatch for "
                                + archive
                                + ": expected "
                                + expectedChecksum
                                + ", got "
                                + actual);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read archive " + archive, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static void extract(Path archive, Path target) {
        try {
            Files.createDirectories(target);
            var name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) extractZip(archive, target);
            else if (name.endsWith(".tar.gz") || name.endsWith(".tgz"))
                extractTar(archive, target, true);
            else if (name.endsWith(".tar.xz") || name.endsWith(".txz"))
                extractTar(archive, target, false);
            else throw new IllegalArgumentException("Unsupported archive format: " + archive);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to extract archive " + archive, e);
        }
    }

    private static void extractZip(Path archive, Path target) throws IOException {
        try (var input = Files.newInputStream(archive);
                var zip = new ZipInputStream(input)) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                var destination = archiveDestination(target, entry.getName());
                if (entry.isDirectory()) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                entry = zip.getNextEntry();
            }
        }
    }

    private static void extractTar(Path archive, Path target, boolean gzip) throws IOException {
        try (var input = Files.newInputStream(archive);
                InputStream decompressed =
                        gzip
                                ? new GzipCompressorInputStream(input)
                                : new XZCompressorInputStream(input);
                var tar = new TarArchiveInputStream(decompressed)) {
            TarArchiveEntry entry = tar.getNextEntry();
            while (entry != null) {
                var destination = archiveDestination(target, entry.getName());
                if (entry.isSymbolicLink() || entry.isLink())
                    extractLink(target, destination, entry.getLinkName());
                else if (entry.isDirectory()) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING);
                    if ((entry.getMode() & 0111) != 0) makeExecutable(destination);
                }
                entry = tar.getNextEntry();
            }
        }
    }

    private static void extractLink(Path target, Path destination, String linkName)
            throws IOException {
        var resolved = destination.getParent().resolve(linkName).normalize();
        if (!resolved.startsWith(target))
            throw new IllegalArgumentException(
                    "Archive link escapes extraction directory: " + linkName);
        Files.createDirectories(destination.getParent());
        Files.createSymbolicLink(destination, Path.of(linkName));
    }

    private static Path archiveDestination(Path target, String entryName) {
        var destination = target.resolve(entryName).normalize();
        if (!destination.startsWith(target))
            throw new IllegalArgumentException(
                    "Archive entry escapes extraction directory: " + entryName);
        return destination;
    }

    private static void makeExecutable(Path path) throws IOException {
        try {
            var permissions = EnumSet.noneOf(PosixFilePermission.class);
            permissions.addAll(Files.getPosixFilePermissions(path));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException e) {
            if (!path.toFile().setExecutable(true, false))
                throw new IOException("Unable to make executable: " + path, e);
        }
    }

    private static Path sourceRoot(Path extracted) {
        try (var paths = Files.list(extracted)) {
            var children = paths.toList();
            if (children.size() == 1 && Files.isDirectory(children.getFirst()))
                return children.getFirst();
            return extracted;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to inspect extracted archive", e);
        }
    }

    private static Path createTemporaryDirectory(Path target) {
        try {
            Files.createDirectories(target.getParent());
            return Files.createTempDirectory(target.getParent(), ".wstool-import-");
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create import directory", e);
        }
    }

    private static void copyDescriptor(Path source, Path target) {
        try {
            var wstool = source.resolve("wstool.pkl");
            Files.copy(wstool, target.resolve("wstool.pkl"), StandardCopyOption.REPLACE_EXISTING);
            var bespokeTools = source.resolve("wstool/bin");
            if (Files.isDirectory(bespokeTools)) copy(bespokeTools, target.resolve("wstool/bin"));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to copy package descriptor", e);
        }
    }

    private static void delete(Path path) {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            item -> {
                                try {
                                    Files.deleteIfExists(item);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to delete import directory", e);
        }
    }

    private static void copy(Path source, Path target) {
        try (var paths = Files.walk(source)) {
            paths.forEach(
                    path -> {
                        var destination = target.resolve(source.relativize(path).toString());
                        try {
                            if (Files.isSymbolicLink(path)) {
                                Files.createDirectories(destination.getParent());
                                Files.createSymbolicLink(destination, Files.readSymbolicLink(path));
                            } else if (Files.isDirectory(path))
                                Files.createDirectories(destination);
                            else {
                                Files.createDirectories(destination.getParent());
                                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to clone " + source, e);
        }
    }
}
