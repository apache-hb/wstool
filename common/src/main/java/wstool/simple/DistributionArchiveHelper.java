package wstool.simple;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import wstool.Distribution.Archive;
import wstool.simple.archive.ImmutableExtractArchiveInput;

/** Downloads and extracts immutable package archives into the workspace metadata cache. */
public class DistributionArchiveHelper {
    private final ArchiveHelper archives = new ArchiveHelper();

    public void extract(Archive archive, Path workspace, Path destination) throws Exception {
        String checksum = archive.getChecksum().toLowerCase();
        Path cache =
                workspace
                        .resolve("private/cache/archives")
                        .resolve(archive.getName() + "-" + archive.getVersion() + "-" + checksum);
        Files.createDirectories(cache.getParent());
        if (!Files.isRegularFile(cache) || !checksum.equals(sha256(cache))) {
            Files.deleteIfExists(cache);
            download(archive.getUri(), cache);
            String actual = sha256(cache);
            if (!checksum.equals(actual)) {
                Files.deleteIfExists(cache);
                throw new IOException(
                        "SHA-256 checksum mismatch for package archive "
                                + archive.getName()
                                + "@"
                                + archive.getVersion()
                                + ": expected "
                                + checksum
                                + " but received "
                                + actual);
            }
        }
        deleteTree(destination);
        Files.createDirectories(destination);
        archives.extract(
                ImmutableExtractArchiveInput.builder()
                        .archive(cache)
                        .destination(destination)
                        .build());
    }

    private void download(URI uri, Path destination) throws Exception {
        Path temporary = Files.createTempFile(destination.getParent(), "package-archive-", ".part");
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Files.copy(Path.of(uri), temporary, StandardCopyOption.REPLACE_EXISTING);
            } else {
                HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
                HttpResponse<Path> response =
                        HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.NORMAL)
                                .build()
                                .send(request, HttpResponse.BodyHandlers.ofFile(temporary));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException(
                            "Unable to download package archive "
                                    + uri
                                    + ": HTTP "
                                    + response.statusCode());
                }
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }
}
