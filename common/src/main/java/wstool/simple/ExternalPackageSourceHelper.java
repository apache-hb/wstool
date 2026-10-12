package wstool.simple;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import wstool.Package.ExternalArchive;
import wstool.Package.ExternalFiles;
import wstool.Package.ExternalGitRepository;
import wstool.Package.ExternalPackage;
import wstool.simple.git.GitRepositoryLocation;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.git.ImmutableGitRepositoryLocation;

/** Materializes third-party package sources and applies their configured patches. */
@Singleton
public class ExternalPackageSourceHelper {
    private final DistributionArchiveHelper archives;
    private final PackageSourceHelper sources;
    private final ExternalFilesHelper files;
    private final GitHelper git;

    @Inject
    public ExternalPackageSourceHelper(
            DistributionArchiveHelper archives,
            PackageSourceHelper sources,
            ExternalFilesHelper files,
            GitHelper git) {

        this.archives = archives;
        this.sources = sources;
        this.files = files;
        this.git = git;
    }

    public Path materialize(ExternalPackage source, String name, String version, Path workspace)
            throws Exception {
        Path destination =
                workspace.resolve("private/cache/external-sources").resolve(name).resolve(version);
        if (source instanceof ExternalArchive archive) {
            Path metadata = destination.resolveSibling(destination.getFileName() + ".metadata");
            String signature = archiveSignature(archive);
            if (!Files.isDirectory(destination)
                    || !Files.isRegularFile(metadata)
                    || !signature.equals(Files.readString(metadata))) {
                deleteTree(destination);
                if (archive.getStripPrefix() == null || archive.getStripPrefix().isBlank()) {
                    archives.extract(archive, name, version, workspace, destination);
                } else {
                    Path extracted =
                            destination.resolveSibling(destination.getFileName() + "-archive");
                    deleteTree(extracted);
                    try {
                        archives.extract(archive, name, version, workspace, extracted);
                        Path prefix = extracted.resolve(archive.getStripPrefix()).normalize();
                        if (!prefix.startsWith(extracted.normalize())
                                || !Files.isDirectory(prefix)) {
                            throw new IOException(
                                    "Archive prefix does not exist: " + archive.getStripPrefix());
                        }
                        sources.copy(prefix, destination);
                    } finally {
                        deleteTree(extracted);
                    }
                }
                Files.writeString(metadata, signature);
            }
            return destination;
        }
        if (source instanceof ExternalFiles externalFiles) {
            files.materialize(externalFiles, name, version, workspace, destination);
            return destination;
        }
        if (source instanceof ExternalGitRepository repository) {
            if (!Files.isDirectory(destination.resolve(".git"))) {
                deleteTree(destination);
                GitRepositoryLocation location =
                        ImmutableGitRepositoryLocation.builder()
                                .repository(repository.getRepo())
                                .branch(repository.getBranch())
                                .build();
                git.clone(
                        ImmutableGitCloneInput.builder()
                                .parent(destination.getParent())
                                .name(destination.getFileName().toString())
                                .repository(location)
                                .build());
            }
            return destination;
        }
        throw new IllegalArgumentException(
                "Unsupported external package source type: " + source.getClass().getSimpleName());
    }

    private String archiveSignature(ExternalArchive archive) {
        return archive.getArchive()
                + "\n"
                + archive.getChecksum()
                + "\n"
                + (archive.getStripPrefix() == null ? "" : archive.getStripPrefix());
    }

    public void mergeAndPatch(
            Path externalSource, Path localSource, Path destination, List<String> patches)
            throws Exception {
        sources.copy(externalSource, destination);
        deleteTree(destination.resolve(".git"));
        sources.overlay(localSource, destination);
        for (int index = 0; index < patches.size(); index++) {
            applyPatch(destination, patches.get(index), index);
        }
    }

    private void applyPatch(Path source, String content, int index) throws Exception {
        Path patchFile = Files.createTempFile("wstool-import-patch-", "-" + index + ".patch");
        try {
            Files.writeString(patchFile, content, StandardCharsets.UTF_8);
            Process process =
                    new ProcessBuilder(
                                    "patch",
                                    "--batch",
                                    "--forward",
                                    "--fuzz=0",
                                    "-p1",
                                    "-i",
                                    patchFile.toString())
                            .directory(source.toFile())
                            .redirectErrorStream(true)
                            .start();
            byte[] output = process.getInputStream().readAllBytes();
            int status = process.waitFor();
            if (status != 0) {
                throw new IOException(
                        "Unable to apply import patch "
                                + (index + 1)
                                + ": "
                                + new String(output, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(patchFile);
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }
}
