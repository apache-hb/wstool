package wstool.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Persists local Git fixtures between integration test runs. */
final class IntegrationRepositoryCache {
    private IntegrationRepositoryCache() {}

    static Path createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create integration cache " + directory, e);
        }
    }

    static void publish(
            String name,
            Path source,
            Path workingRoot,
            Path repositoryRoot,
            Path stateRoot,
            boolean persistent)
            throws IOException, InterruptedException {
        var repository = repositoryRoot.resolve(name + ".git");
        var marker = stateRoot.resolve(name + ".sha256");
        var fingerprint = fingerprint(source);
        if (persistent
                && Files.isDirectory(repository)
                && Files.isRegularFile(marker)
                && Files.readString(marker).equals(fingerprint)) return;

        Files.createDirectories(workingRoot);
        Files.createDirectories(repositoryRoot);
        Files.createDirectories(stateRoot);
        deleteTree(repository);
        Files.deleteIfExists(marker);
        var working = workingRoot.resolve(name);
        deleteTree(working);
        copyTree(source, working);
        run(working, "git", "init", "--initial-branch", "master");
        run(working, "git", "config", "user.name", "wstool integration");
        run(working, "git", "config", "user.email", "wstool@example.invalid");
        run(working, "git", "add", ".");
        run(working, "git", "commit", "-m", "fixture");
        run(null, "git", "clone", "--bare", working.toString(), repository.toString());
        if (persistent) Files.writeString(marker, fingerprint);
    }

    private static String fingerprint(Path source) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(source)) {
                for (var path : paths.sorted().toList()) {
                    var relative = source.relativize(path).toString();
                    digest.update(relative.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    if (Files.isSymbolicLink(path)) {
                        digest.update(
                                Files.readSymbolicLink(path)
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8));
                    } else if (Files.isRegularFile(path)) {
                        digest.update(Files.readAllBytes(path));
                        digest.update((byte) (Files.isExecutable(path) ? 1 : 0));
                    }
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var destination = target.resolve(source.relativize(path).toString());
                if (Files.isSymbolicLink(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.createSymbolicLink(destination, Files.readSymbolicLink(path));
                } else if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(
                            path,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true, false);
                path.toFile().setExecutable(true, false);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void run(Path directory, String... command)
            throws IOException, InterruptedException {
        var builder = new ProcessBuilder(List.of(command)).redirectErrorStream(true);
        if (directory != null) builder.directory(directory.toFile());
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    "Command failed: "
                            + String.join(" ", command)
                            + System.lineSeparator()
                            + output);
        }
    }
}
