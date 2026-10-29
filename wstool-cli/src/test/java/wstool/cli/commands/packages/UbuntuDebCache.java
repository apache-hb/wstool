package wstool.cli.commands.packages;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.file.PathUtils;
import wstool.common.path.DownloadManager;
import wstool.common.path.DownloadRequest;

/**
 * Downloads Ubuntu package files once and prepares source archives for the test package catalog.
 */
public final class UbuntuDebCache {
    private static final String UBUNTU_RELEASE = "resolute";
    private static final String UBUNTU_ARCHIVE = "https://archive.ubuntu.com/ubuntu/";
    private static final List<String> COMPONENTS = List.of("main", "universe");

    private final DownloadManager downloadManager;
    private final String architecture;
    private final Path cacheRoot;

    public UbuntuDebCache(DownloadManager downloadManager) {
        this.downloadManager = downloadManager;
        this.architecture = architecture();
        this.cacheRoot =
                Path.of(
                        System.getProperty("user.home"),
                        ".cache",
                        "wstool",
                        "ubuntu",
                        UBUNTU_RELEASE,
                        architecture);
    }

    public UbuntuDebArtifact get(String packageName) throws Exception {
        UbuntuPackageMetadata metadata = metadata(packageName);
        Path packageCache =
                cacheRoot.resolve("extracted-v4").resolve(packageName).resolve(metadata.version());
        Path dataArchive = packageCache.resolve("data.tar.gz");
        Path controlFile = packageCache.resolve("control");
        try {
            return artifact(packageName, metadata, dataArchive, controlFile);
        } catch (NoSuchFileException exception) {
            preparePackage(metadata, packageCache, dataArchive, controlFile);
        }
        return artifact(packageName, metadata, dataArchive, controlFile);
    }

    public UbuntuDebFile getDeb(String packageName) throws Exception {
        UbuntuPackageMetadata metadata = metadata(packageName);
        Path deb =
                downloadManager.get(
                        DownloadRequest.builder(
                                        cacheRoot.resolve("downloads/sources/downloads"),
                                        URI.create(UBUNTU_ARCHIVE + metadata.filename()))
                                .checksum(metadata.checksum())
                                .build());
        return new UbuntuDebFile(packageName, deb, metadata.checksum());
    }

    private UbuntuDebArtifact artifact(
            String packageName, UbuntuPackageMetadata metadata, Path dataArchive, Path controlFile)
            throws Exception {
        return new UbuntuDebArtifact(
                packageName,
                metadata.version(),
                dataArchive,
                DownloadManager.sha256(dataArchive),
                controlFile,
                DownloadManager.sha256(controlFile));
    }

    private UbuntuPackageMetadata metadata(String packageName) throws Exception {
        for (String component : COMPONENTS) {
            Path index = packageIndex(component);
            try (InputStream gzip = Files.newInputStream(index);
                    var reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            new GZIPInputStream(gzip), StandardCharsets.UTF_8))) {
                Map<String, String> fields = new LinkedHashMap<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (packageName.equals(fields.get("Package"))) {
                            String packageArchitecture = fields.get("Architecture");
                            if (packageArchitecture.equals("all")
                                    || packageArchitecture.equals(architecture)) {
                                return new UbuntuPackageMetadata(
                                        fields.get("Version"),
                                        fields.get("Filename"),
                                        fields.get("SHA256"));
                            }
                        }
                        fields.clear();
                    } else if (!line.startsWith(" ") && !line.startsWith("\t")) {
                        int separator = line.indexOf(':');
                        if (separator > 0) {
                            fields.put(
                                    line.substring(0, separator),
                                    line.substring(separator + 1).strip());
                        }
                    }
                }
            }
        }
        throw new IOException(
                "Ubuntu " + UBUNTU_RELEASE + " repositories do not contain package " + packageName);
    }

    private Path packageIndex(String component) throws Exception {
        URI uri =
                URI.create(
                        UBUNTU_ARCHIVE
                                + "dists/"
                                + UBUNTU_RELEASE
                                + "/"
                                + component
                                + "/binary-"
                                + architecture
                                + "/Packages.gz");
        return downloadManager.get(
                DownloadRequest.builder(cacheRoot.resolve("indexes"), uri)
                        .cacheName("index-" + component + ".gz")
                        .build());
    }

    private void preparePackage(
            UbuntuPackageMetadata metadata, Path packageCache, Path dataArchive, Path controlFile)
            throws Exception {
        Files.createDirectories(packageCache.getParent());
        Path deb =
                downloadManager.get(
                        DownloadRequest.builder(
                                        cacheRoot.resolve("downloads/sources/downloads"),
                                        URI.create(UBUNTU_ARCHIVE + metadata.filename()))
                                .checksum(metadata.checksum())
                                .build());
        Path staging = Files.createTempDirectory(packageCache.getParent(), ".deb-extract-");
        try {
            Path configuration = staging.resolve("configuration");
            Path controlDirectory = staging.resolve("control");
            Files.createDirectories(configuration);
            Files.createDirectories(controlDirectory);
            runDpkgDeb(List.of("--extract", deb.toString(), configuration.toString()));
            runDpkgDeb(List.of("--control", deb.toString(), controlDirectory.toString()));
            Path control = controlDirectory.resolve("control");
            String controlContents = Files.readString(control);

            Path temporaryArchive = Files.createTempFile(packageCache.getParent(), ".data-", ".gz");
            Path temporaryControl =
                    Files.createTempFile(packageCache.getParent(), ".control-", ".tmp");
            try {
                createArchive(configuration, temporaryArchive);
                Files.writeString(temporaryControl, controlContents);
                Files.createDirectories(packageCache);
                moveIntoPlace(temporaryArchive, dataArchive);
                moveIntoPlace(temporaryControl, controlFile);
            } finally {
                Files.deleteIfExists(temporaryArchive);
                Files.deleteIfExists(temporaryControl);
            }
        } finally {
            PathUtils.deleteDirectory(staging);
        }
    }

    private void createArchive(Path source, Path destination) throws IOException {
        try (var fileOutput = Files.newOutputStream(destination);
                var compressed =
                        new GzipCompressorOutputStream(new BufferedOutputStream(fileOutput));
                var archive = new TarArchiveOutputStream(compressed)) {
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            archive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            List<Path> entries;
            try (var paths = Files.walk(source)) {
                entries = paths.sorted().toList();
            }
            for (Path path : entries) {
                if (path.equals(source)) {
                    continue;
                }
                String relative =
                        source.relativize(path)
                                .toString()
                                .replace(path.getFileSystem().getSeparator(), "/");
                BasicFileAttributes attributes =
                        Files.readAttributes(
                                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                int mode =
                        ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS))
                                        .intValue()
                                & 07777;
                TarArchiveEntry entry;
                if (attributes.isSymbolicLink()) {
                    entry =
                            new TarArchiveEntry(
                                    "configuration/" + relative, TarConstants.LF_SYMLINK);
                    entry.setLinkName(Files.readSymbolicLink(path).toString());
                    entry.setSize(0);
                    entry.setMode(0777);
                } else if (attributes.isDirectory()) {
                    entry =
                            new TarArchiveEntry(
                                    "configuration/" + relative + "/", TarConstants.LF_DIR);
                    entry.setMode(mode);
                } else {
                    entry =
                            new TarArchiveEntry(
                                    "configuration/" + relative, TarConstants.LF_NORMAL);
                    entry.setMode(mode);
                    entry.setSize(attributes.size());
                }
                entry.setModTime(attributes.lastModifiedTime().toMillis());
                entry.setUserId(0);
                entry.setGroupId(0);
                entry.setUserName("");
                entry.setGroupName("");
                archive.putArchiveEntry(entry);
                if (attributes.isRegularFile()) {
                    try (InputStream input = Files.newInputStream(path)) {
                        input.transferTo(archive);
                    }
                }
                archive.closeArchiveEntry();
            }
            archive.finish();
        }
    }

    private void runDpkgDeb(List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("dpkg-deb");
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int status = process.waitFor();
        if (status != 0) {
            throw new IOException("dpkg-deb failed: " + output);
        }
    }

    private String architecture() {
        return switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new IllegalStateException("Unsupported Ubuntu package architecture");
        };
    }

    private void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
