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
import java.util.Map;
import wstool.Package.ExternalFile;
import wstool.Package.ExternalFiles;

/** Downloads and verifies individual files that make up an external package source. */
public class ExternalFilesHelper {
    private final HttpClient client = HttpClient.newHttpClient();

    public void materialize(
            ExternalFiles source, String name, String version, Path workspace, Path destination)
            throws Exception {
        deleteTree(destination);
        Files.createDirectories(destination);
        for (Map.Entry<String, ExternalFile> entry : source.getFiles().entrySet()) {
            Path relativePath = Path.of(entry.getKey()).normalize();
            if (relativePath.toString().isEmpty()
                    || relativePath.isAbsolute()
                    || relativePath.startsWith("..")) {
                throw new IllegalArgumentException(
                        "External file path must stay within the package: " + entry.getKey());
            }
            ExternalFile file = entry.getValue();
            Path cached = cache(file, name, version, workspace);
            Path target = destination.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.copy(
                    cached,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            if (file.isExecutable() && !target.toFile().setExecutable(true, false)) {
                throw new IOException("Unable to mark external file executable: " + target);
            }
        }
    }

    private Path cache(ExternalFile file, String name, String version, Path workspace)
            throws Exception {
        String checksum = file.getChecksum().toLowerCase();
        if (!checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "External file checksum must be a 64-character SHA-256 value: "
                            + file.getChecksum());
        }
        Path cached =
                workspace
                        .resolve("private/cache/external-files")
                        .resolve(name)
                        .resolve(version)
                        .resolve(checksum);
        Files.createDirectories(cached.getParent());
        if (Files.isRegularFile(cached) && checksum.equals(sha256(cached))) {
            return cached;
        }
        Files.deleteIfExists(cached);
        IOException failures = new IOException("Unable to fetch external file " + file.getUri());
        var locations = new java.util.ArrayList<URI>();
        locations.add(file.getUri());
        locations.addAll(file.getMirrors());
        for (URI location : locations) {
            try {
                download(location, cached);
                String actual = sha256(cached);
                if (checksum.equals(actual)) {
                    return cached;
                }
                Files.deleteIfExists(cached);
                failures.addSuppressed(
                        new IOException(
                                "SHA-256 checksum mismatch from "
                                        + location
                                        + ": expected "
                                        + checksum
                                        + " but received "
                                        + actual));
            } catch (Exception error) {
                Files.deleteIfExists(cached);
                failures.addSuppressed(error);
            }
        }
        throw failures;
    }

    private void download(URI uri, Path destination) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Files.copy(Path.of(uri), destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<Path> response =
                client.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(destination);
            throw new IOException(
                    "Unable to download external file from "
                            + uri
                            + ": HTTP "
                            + response.statusCode());
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
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }
}
