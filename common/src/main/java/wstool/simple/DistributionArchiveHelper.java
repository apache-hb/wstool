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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import wstool.Distribution.Archive;
import wstool.Package.ExternalArchive;
import wstool.simple.archive.ImmutableExtractArchiveInput;

/** Downloads and extracts immutable package archives into the workspace metadata cache. */
public class DistributionArchiveHelper {
    private final ArchiveHelper archives = new ArchiveHelper();

    public void extract(Archive archive, Path workspace, Path destination) throws Exception {
        extract(
                archive.getName(),
                archive.getVersion(),
                archive.getUri(),
                List.of(),
                archive.getChecksum(),
                workspace,
                destination);
    }

    public void extract(
            ExternalArchive archive, String name, String version, Path workspace, Path destination)
            throws Exception {
        extract(
                name,
                version,
                archive.getArchive(),
                archive.getMirrors(),
                archive.getChecksum(),
                workspace,
                destination);
    }

    private void extract(
            String name,
            String version,
            URI uri,
            List<URI> mirrors,
            String expectedChecksum,
            Path workspace,
            Path destination)
            throws Exception {
        String checksum = expectedChecksum;
        Path cache =
                workspace
                        .resolve("private/cache/archives")
                        .resolve(
                                name
                                        + "-"
                                        + version
                                        + "-"
                                        + HexFormat.of()
                                                .formatHex(
                                                        MessageDigest.getInstance("SHA-256")
                                                                .digest(
                                                                        checksum.getBytes(
                                                                                java.nio.charset
                                                                                        .StandardCharsets
                                                                                        .UTF_8))));
        Files.createDirectories(cache.getParent());
        if (!Files.isRegularFile(cache) || !matchesChecksum(cache, checksum)) {
            Files.deleteIfExists(cache);
            List<URI> locations = new ArrayList<>();
            locations.add(uri);
            locations.addAll(mirrors);
            downloadAndVerify(locations, cache, checksum, name, version);
        }
        deleteTree(destination);
        Files.createDirectories(destination);
        archives.extract(
                ImmutableExtractArchiveInput.builder()
                        .archive(cache)
                        .destination(destination)
                        .build());
    }

    private void downloadAndVerify(
            List<URI> locations, Path cache, String checksum, String name, String version)
            throws Exception {
        IOException failures =
                new IOException("Unable to fetch package archive " + name + "@" + version);
        for (URI location : locations) {
            try {
                download(location, cache);
                if (matchesChecksum(cache, checksum)) {
                    return;
                }
                String actual = sha256(cache);
                Files.deleteIfExists(cache);
                failures.addSuppressed(
                        new IOException(
                                "Archive integrity mismatch from "
                                        + location
                                        + ": expected "
                                        + checksum
                                        + " but received "
                                        + actual));
            } catch (Exception error) {
                Files.deleteIfExists(cache);
                failures.addSuppressed(error);
            }
        }
        throw failures;
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

    private boolean matchesChecksum(Path path, String expected) throws Exception {
        if (expected.matches("(?i)[0-9a-f]{64}")) {
            return expected.equalsIgnoreCase(sha256(path));
        }
        for (String token : expected.split("\\s+")) {
            int separator = token.indexOf('-');
            if (separator <= 0) {
                continue;
            }
            String algorithm = token.substring(0, separator);
            String encoded = token.substring(separator + 1).split("\\?", 2)[0];
            try {
                byte[] expectedDigest = Base64.getDecoder().decode(encoded);
                MessageDigest digest =
                        MessageDigest.getInstance(algorithm.toUpperCase(Locale.ROOT));
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, count);
                    }
                }
                if (MessageDigest.isEqual(expectedDigest, digest.digest())) {
                    return true;
                }
            } catch (IllegalArgumentException | java.security.NoSuchAlgorithmException ignored) {
                // Try another SRI token when this digest algorithm is unavailable or malformed.
            }
        }
        return false;
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
