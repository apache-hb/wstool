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
import java.util.HexFormat;
import java.util.List;
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
        String checksum = expectedChecksum.toLowerCase();
        Path cache =
                workspace
                        .resolve("private/cache/archives")
                        .resolve(name + "-" + version + "-" + checksum);
        Files.createDirectories(cache.getParent());
        if (!Files.isRegularFile(cache) || !checksum.equals(sha256(cache))) {
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
                String actual = sha256(cache);
                if (checksum.equals(actual)) {
                    return;
                }
                Files.deleteIfExists(cache);
                failures.addSuppressed(
                        new IOException(
                                "SHA-256 checksum mismatch from "
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
