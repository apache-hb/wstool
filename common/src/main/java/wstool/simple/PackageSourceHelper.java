package wstool.simple;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
public class PackageSourceHelper {
    private final DistributionArchiveHelper archives = new DistributionArchiveHelper();

    public Path materialize(PackageSource source, Path workspace, GitHelper git) throws Exception {
        Path cache =
                workspace
                        .resolve("private/cache/package-sources")
                        .resolve(source.getName())
                        .resolve(source.getVersion());
        if (source instanceof Archive archive) {
            if (!Files.isDirectory(cache)) {
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
            Path parent = materialize(nested.getFrom(), workspace, git);
            Path resolved = parent.resolve(nested.getPath()).normalize();
            if (!resolved.startsWith(parent.normalize())) {
                throw new IOException(
                        "Package source path escapes its parent: " + nested.getPath());
            }
            if (!Files.isRegularFile(resolved.resolve("wstool.pkl"))) {
                throw new IOException(
                        "Package configuration not found: " + resolved.resolve("wstool.pkl"));
            }
            return resolved;
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
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }
                });
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
