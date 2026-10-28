package wstool.common.path;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.file.PathUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import wstool.common.distribution.StorageConfig;
import wstool.common.model.DependencyType;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageConfig;
import wstool.common.packages.PackageFactory;
import wstool.common.workspace.LocalWorkspace;

@Singleton
public class PackageArtifactStore {
    private final ArchiveExtractor archiveExtractor;
    private final PackageFactory packageFactory;

    @Inject
    public PackageArtifactStore(ArchiveExtractor archiveExtractor, PackageFactory packageFactory) {
        this.archiveExtractor = archiveExtractor;
        this.packageFactory = packageFactory;
    }

    public void store(LocalWorkspace workspace, PackageConfig pkg) throws Exception {
        Path prefix = workspace.prefix(pkg.name());
        if (!isDirectory(prefix)) {
            throw new IllegalArgumentException(
                    "No build artifacts found for package " + pkg.name() + " at " + prefix);
        }

        Path archive = Files.createTempFile(workspace.cache(), "package-artifact-", ".tar.gz");
        try {
            createArchive(prefix, archive);
            StorageConfig storage = workspace.artifactStorage();
            try (S3Client client = createClient(storage)) {
                client.putObject(
                        PutObjectRequest.builder()
                                .bucket(storage.bucket())
                                .key(objectKey(pkg.name()))
                                .build(),
                        RequestBody.fromFile(archive));
                client.putObject(
                        PutObjectRequest.builder()
                                .bucket(storage.bucket())
                                .key(metadataKey(pkg.name()))
                                .build(),
                        RequestBody.fromFile(
                                workspace.source(pkg.name()).resolve("wstool.pkl")));
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    public void ensureAvailable(LocalWorkspace workspace, PackageMajorVersion name)
            throws Exception {
        Path source = workspace.source(name);
        if (isRegularFile(source.resolve("wstool.pkl"))) {
            return;
        }

        Path prefix = workspace.prefix(name);
        Path cacheDirectory =
                workspace.cache().resolve("artifacts").resolve(name.name()).resolve(name.version());
        Path archive = cacheDirectory.resolve("package.tar.gz");
        Path marker = cacheDirectory.resolve("extracted");
        if (isRegularFile(marker) && isDirectory(prefix)) {
            return;
        }

        Files.createDirectories(cacheDirectory);
        downloadToCache(workspace, objectKey(name), archive);

        deleteDirectoryIfPresent(prefix);
        archiveExtractor.extract(archive, prefix, 0);
        Files.createDirectories(marker.getParent());
        Files.writeString(
                marker,
                "extracted\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    public Set<PackageMajorVersion> ensureRuntimeClosure(
            LocalWorkspace workspace, PackageMajorVersion name) throws Exception {
        Set<PackageMajorVersion> closure = new LinkedHashSet<>();
        Set<PackageMajorVersion> visiting = new HashSet<>();
        ensureRuntimeClosure(workspace, name, closure, visiting);
        return closure;
    }

    private void ensureRuntimeClosure(
            LocalWorkspace workspace,
            PackageMajorVersion name,
            Set<PackageMajorVersion> closure,
            Set<PackageMajorVersion> visiting)
            throws Exception {
        if (closure.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            throw new IllegalArgumentException("Dependency cycle detected while resolving " + name);
        }

        Path source = workspace.source(name);
        boolean local = isRegularFile(source.resolve("wstool.pkl"));
        if (!local) {
            ensureAvailable(workspace, name);
        }
        PackageConfig config =
                local ? packageFactory.open(source) : openStoredPackageConfig(workspace, name);
        closure.add(name);
        for (var dependency : config.dependencies().entrySet()) {
            if (dependency.getKey() == DependencyType.RUNTIME
                    || dependency.getKey() == DependencyType.PUBLIC) {
                for (PackageMajorVersion runtimeDependency : dependency.getValue()) {
                    ensureRuntimeClosure(workspace, runtimeDependency, closure, visiting);
                }
            }
        }
        visiting.remove(name);
    }

    private PackageConfig openStoredPackageConfig(LocalWorkspace workspace, PackageMajorVersion name)
            throws Exception {
        Path cacheDirectory =
                workspace.cache().resolve("artifacts").resolve(name.name()).resolve(name.version());
        Path manifest = cacheDirectory.resolve("wstool.pkl");
        if (!isRegularFile(manifest)) {
            downloadToCache(workspace, metadataKey(name), manifest);
        }
        return packageFactory.open(cacheDirectory);
    }

    private void downloadToCache(LocalWorkspace workspace, String key, Path destination)
            throws Exception {
        Files.createDirectories(destination.getParent());
        Path download = Files.createTempFile(destination.getParent(), "artifact-download-", ".tmp");
        try {
            Files.delete(download);
            StorageConfig storage = workspace.artifactStorage();
            try (S3Client client = createClient(storage)) {
                client.getObject(
                        GetObjectRequest.builder()
                                .bucket(storage.bucket())
                                .key(key)
                                .build(),
                        ResponseTransformer.toFile(download));
            }
            moveIntoPlace(download, destination);
        } finally {
            Files.deleteIfExists(download);
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

    private S3Client createClient(StorageConfig storage) {
        var builder =
                S3Client.builder()
                        .region(DefaultAwsRegionProviderChain.builder().build().getRegion())
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(storage.endpoint() != null)
                                        .chunkedEncodingEnabled(false)
                                        .build());
        if (storage.endpoint() != null) {
            builder.endpointOverride(storage.endpoint());
        }
        return builder.build();
    }

    private void createArchive(Path root, Path destination) throws IOException {
        try (OutputStream file = Files.newOutputStream(destination);
                GzipCompressorOutputStream compressed = new GzipCompressorOutputStream(file);
                TarArchiveOutputStream archive = new TarArchiveOutputStream(compressed)) {
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            archive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path directory, BasicFileAttributes attributes) throws IOException {
                            if (!directory.equals(root)) {
                                addDirectory(archive, root, directory);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                                throws IOException {
                            addFile(archive, root, file, attributes);
                            return FileVisitResult.CONTINUE;
                        }
                    });
            archive.finish();
        }
    }

    private void addDirectory(TarArchiveOutputStream archive, Path root, Path directory)
            throws IOException {
        String name = entryName(root, directory) + "/";
        TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_DIR);
        entry.setMode(0755);
        archive.putArchiveEntry(entry);
        archive.closeArchiveEntry();
    }

    private void addFile(
            TarArchiveOutputStream archive, Path root, Path file, BasicFileAttributes attributes)
            throws IOException {
        String name = entryName(root, file);
        if (attributes.isSymbolicLink()) {
            TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
            entry.setLinkName(Files.readSymbolicLink(file).toString());
            entry.setMode(0777);
            archive.putArchiveEntry(entry);
            archive.closeArchiveEntry();
            return;
        }

        TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_NORMAL);
        entry.setSize(attributes.size());
        entry.setMode(isExecutable(file) ? 0755 : 0644);
        archive.putArchiveEntry(entry);
        Files.copy(file, archive);
        archive.closeArchiveEntry();
    }

    private String entryName(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private String objectKey(PackageMajorVersion name) {
        return "artifacts/" + name.name() + "/" + name.version() + ".tar.gz";
    }

    private String metadataKey(PackageMajorVersion name) {
        return "artifacts/" + name.name() + "/" + name.version() + "/wstool.pkl";
    }

    private boolean isDirectory(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).isDirectory();
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private boolean isRegularFile(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).isRegularFile();
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private boolean isExecutable(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private void deleteDirectoryIfPresent(Path directory) throws IOException {
        try {
            PathUtils.deleteDirectory(directory);
        } catch (NoSuchFileException exception) {
            // Another process removed the cached prefix before replacement.
        }
    }
}
