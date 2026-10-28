package wstool.cli.commands.packages;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import wstool.common.path.DownloadManager;
import wstool.common.path.DownloadRequest;

/** Downloads Corretto for the test distribution's ordinary Corretto package. */
final class CorrettoTestPackageSource {
    private static final String VERSION = "25";

    private final DownloadManager downloadManager;

    CorrettoTestPackageSource(DownloadManager downloadManager) {
        this.downloadManager = downloadManager;
    }

    CachedCorretto get() throws Exception {
        String architecture = architecture();
        String archiveName =
                "amazon-corretto-" + VERSION + "-" + architecture + "-linux-jdk.tar.gz";
        URI archiveUri = URI.create("https://corretto.aws/downloads/latest/" + archiveName);
        URI checksumUri = URI.create("https://corretto.aws/downloads/latest_sha256/" + archiveName);
        Path cache = Path.of(System.getProperty("user.home"), ".cache", "wstool", "corretto");
        String checksumFileName = "package-" + VERSION + "-" + architecture + ".sha256";
        Path checksumFile =
                downloadManager.get(
                        DownloadRequest.builder(cache, checksumUri)
                                .cacheName(checksumFileName)
                                .shareCache(false)
                                .build());
        String checksum =
                Files.readString(checksumFile).strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Invalid cached Corretto SHA-256 in " + checksumFile);
        }
        Path archive =
                downloadManager.get(
                        DownloadRequest.builder(cache.resolve("sources/downloads"), archiveUri)
                                .checksum(checksum)
                                .build());
        return new CachedCorretto(archive, checksum);
    }

    private String architecture() {
        return switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default ->
                    throw new IllegalStateException(
                            "Unsupported architecture for the Corretto package: "
                                    + System.getProperty("os.arch"));
        };
    }
}

record CachedCorretto(Path archive, String checksum) {}
