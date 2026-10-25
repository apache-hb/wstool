package wstool.common.path;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Downloads and verifies archives in the workspace cache. */
@Singleton
public class ArchiveCache {
    private final HttpClient httpClient;

    @Inject
    public ArchiveCache(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public Path get(Path workspaceCache, URI uri, String checksum) throws Exception {
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
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        return cached;
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
            Files.deleteIfExists(destination);
            throw new IOException("Could not download " + uri + ": HTTP " + response.statusCode());
        }
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
        } catch (IOException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
