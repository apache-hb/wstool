package wstool.simple;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import wstool.Distribution.Archive;
import wstool.Distribution.Folder;
import wstool.Distribution.GitRepository;
import wstool.Distribution.PackageSource;
import wstool.simple.git.GitCloneInput;
import wstool.simple.git.GitRepositoryLocation;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.git.ImmutableGitRepositoryLocation;

/**
 * Materializes package sources from archives, repositories, or a path in another package source.
 */
@Singleton
public class PackageSourceHelper {
    private final DistributionArchiveHelper archives;
    private final GitHelper git;
    private final PackageMapper packageMapper;

    @Inject
    public PackageSourceHelper(
            DistributionArchiveHelper archives, GitHelper git, PackageMapper packageMapper) {
        this.archives = archives;
        this.git = git;
        this.packageMapper = packageMapper;
    }

    public Path materialize(PackageSource source, Path workspace) throws Exception {
        LocalPackage resolved = packageMapper.toLocalPackage(source, workspace);
        Path cache = resolved.source();

        if (source instanceof Archive archive) {
            if (!Files.isDirectory(resolved.source())) {
                archives.extract(archive, workspace, cache);
            }
            return cache;
        }

        if (source instanceof GitRepository repository) {
            if (!Files.isDirectory(cache.resolve(".git"))) {
                deleteTree(cache);
                GitRepositoryLocation location =
                        ImmutableGitRepositoryLocation.builder()
                                .repository(repository.getRepo())
                                .branch(repository.getBranch())
                                .build();
                GitCloneInput clone =
                        ImmutableGitCloneInput.builder()
                                .parent(cache.getParent())
                                .name(cache.getFileName().toString())
                                .repository(location)
                                .build();
                git.clone(clone);
            }
            return cache;
        }

        if (source instanceof Folder nested) {
            Path parent = materialize(nested.getFrom(), workspace);
            Path nestedPath = parent.resolve(nested.getPath()).normalize();
            if (!nestedPath.startsWith(parent.normalize())) {
                throw new IOException(
                        "Package source path escapes its parent: " + nested.getPath());
            }
            if (!Files.isRegularFile(nestedPath.resolve("wstool.pkl"))) {
                throw new IOException(
                        "Package configuration not found: " + nestedPath.resolve("wstool.pkl"));
            }
            return nestedPath;
        }
        throw new IllegalArgumentException(
                "Unsupported package source type: " + source.getClass().getSimpleName());
    }

    public void copy(Path source, Path destination) throws IOException {
        deleteTree(destination);
        Files.createDirectories(destination);
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) throws IOException {
                        Files.createDirectories(destination.resolve(source.relativize(directory)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        Path target = destination.resolve(source.relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.copy(
                                file,
                                target,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** Copies files from {@code overlay} over {@code destination}, preserving other files. */
    public void overlay(Path overlay, Path destination) throws IOException {
        Files.walkFileTree(
                overlay,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) throws IOException {
                        Path relative = overlay.relativize(directory);
                        if (relative.getNameCount() > 0
                                && ".git".equals(relative.getName(0).toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        Files.createDirectories(destination.resolve(relative));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        Path relative = overlay.relativize(file);
                        if (relative.getNameCount() > 0
                                && ".git".equals(relative.getName(0).toString())) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path target = destination.resolve(relative);
                        Files.createDirectories(target.getParent());
                        Files.copy(
                                file,
                                target,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }
}
