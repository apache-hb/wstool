package wstool.common.path;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Downloads, verifies, and shares cached files across workspaces. */
@Singleton
public class DownloadManager {
    private final HttpClient httpClient;

    @Inject
    public DownloadManager(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public Path get(DownloadRequest request) throws Exception {
        Path cacheDirectory = request.cacheDirectory();
        String cacheName = request.cacheName();
        URI uri = request.uri();
        String checksum = request.checksum();
        if (cacheName != null
                && (cacheName.isBlank()
                        || Path.of(cacheName).isAbsolute()
                        || Path.of(cacheName).getNameCount() != 1
                        || cacheName.equals(".")
                        || cacheName.equals(".."))) {
            throw new IllegalArgumentException(
                    "Cache name must be a single file name: " + cacheName);
        }
        Files.createDirectories(cacheDirectory);
        String expectedChecksum = normalizeChecksum(checksum);
        String identity =
                expectedChecksum == null
                        ? sha256(uri.toString().getBytes(StandardCharsets.UTF_8))
                        : sha256(expectedChecksum.getBytes(StandardCharsets.UTF_8));
        Path local =
                cacheName == null
                        ? cacheDirectory.resolve(identity)
                        : cacheDirectory.resolve(cacheName);
        Path localChecksum = checksumPath(local);
        Path shared = sharedCache().resolve(identity);
        Path sharedChecksum = checksumPath(shared);

        if (isCached(local, localChecksum, expectedChecksum)) {
            return local;
        }
        if (request.shareCache() && isCached(shared, sharedChecksum, expectedChecksum)) {
            linkOrCopy(shared, local);
            if (expectedChecksum == null) {
                writeChecksum(localChecksum, readChecksum(sharedChecksum));
            }
            return local;
        }

        Path temporary = Files.createTempFile(cacheDirectory, ".download-", ".tmp");
        try {
            download(uri, temporary);
            String actualChecksum = sha256(temporary);
            if (expectedChecksum != null && !expectedChecksum.equals(actualChecksum)) {
                throw new IOException(
                        "SHA-256 mismatch for "
                                + uri
                                + ": expected "
                                + expectedChecksum
                                + ", got "
                                + actualChecksum);
            }

            if (request.shareCache()) {
                Files.createDirectories(shared.getParent());
                linkOrCopy(temporary, shared);
                if (expectedChecksum == null) {
                    writeChecksum(sharedChecksum, actualChecksum);
                }
            }
            linkOrCopy(temporary, local);
            if (expectedChecksum == null) {
                writeChecksum(localChecksum, actualChecksum);
            }
            return local;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String sha256(Path file) throws Exception {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private boolean isCached(Path file, Path checksumFile, String expectedChecksum)
            throws IOException {
        try {
            if (!Files.readAttributes(file, BasicFileAttributes.class).isRegularFile()) {
                return false;
            }
        } catch (NoSuchFileException exception) {
            return false;
        }
        return expectedChecksum != null || readChecksumIfPresent(checksumFile) != null;
    }

    private String readChecksumIfPresent(Path checksumFile) throws IOException {
        String checksum;
        try {
            checksum = Files.readString(checksumFile).strip().toLowerCase(Locale.ROOT);
        } catch (NoSuchFileException exception) {
            return null;
        }
        return checksum.matches("[0-9a-f]{64}") ? checksum : null;
    }

    private String readChecksum(Path checksumFile) throws IOException {
        String checksum = readChecksumIfPresent(checksumFile);
        if (checksum == null) {
            throw new IOException("Missing cached checksum file: " + checksumFile);
        }
        return checksum;
    }

    private String normalizeChecksum(String checksum) throws IOException {
        if (checksum == null) {
            return null;
        }
        String normalized = checksum.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid SHA-256 checksum: " + checksum);
        }
        return normalized;
    }

    private void download(URI uri, Path destination) throws Exception {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.equalsIgnoreCase("file")) {
            Path source = scheme == null ? Path.of(uri.toString()) : Path.of(uri);
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            throw new IOException("Unsupported source URI scheme: " + uri);
        }

        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<Path> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Could not download " + uri + ": HTTP " + response.statusCode());
        }
    }

    private Path checksumPath(Path file) {
        return file.resolveSibling(file.getFileName() + ".download-sha256");
    }

    private Path sharedCache() {
        return Path.of(System.getProperty("user.home"), ".cache", "wstool", "sources", "downloads");
    }

    private void writeChecksum(Path checksumFile, String checksum) throws IOException {
        Files.createDirectories(checksumFile.getParent());
        Path temporary = Files.createTempFile(checksumFile.getParent(), ".checksum-", ".tmp");
        try {
            Files.writeString(temporary, checksum + System.lineSeparator());
            moveIntoPlace(temporary, checksumFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void linkOrCopy(Path source, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".cache-copy-", ".tmp");
        try {
            Files.delete(temporary);
            try {
                Files.createLink(temporary, source);
            } catch (IOException | UnsupportedOperationException exception) {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            moveIntoPlace(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
