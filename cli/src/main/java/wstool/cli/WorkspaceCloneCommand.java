package wstool.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.PackageDescriptorReader;
import wstool.common.WorkspaceLayout;
import wstool.common.model.PackageDescriptor;

@Command(
        name = "clone",
        description = "Clone a local package repository into the workspace.",
        mixinStandardHelpOptions = true)
public final class WorkspaceCloneCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(WorkspaceCloneCommand.class);

    @Option(names = "--package", required = true, arity = "1..")
    List<String> packages;

    @Option(
            names = "--from",
            description = "Local repository directory or git URL; may be used once per package.")
    String from;

    @Override
    public Integer call() {
        var layout = WorkspaceLayout.find(Path.of("."));
        for (var packageName : packages) {
            var source = from == null ? packageName : from;
            var targetName = packageName;
            if (isUrl(source)) {
                if (packages.size() != 1)
                    throw new IllegalArgumentException("A git URL must be cloned separately");
                targetName = repositoryName(source);
                gitClone(source, layout.sources().resolve(targetName));
            } else {
                var sourcePath = Path.of(source).toAbsolutePath().normalize();
                var targetPath = layout.sources().resolve(targetName);
                var descriptor = PackageDescriptorReader.read(sourcePath);
                if (descriptor.importOptions() == null) copy(sourcePath, targetPath);
                else importPackage(sourcePath, targetPath, descriptor);
            }
            LOG.info("Cloned {}", targetName);
        }
        return 0;
    }

    private static boolean isUrl(String value) {
        return value.startsWith("https://")
                || value.startsWith("http://")
                || value.startsWith("git@");
    }

    private static String repositoryName(String url) {
        var value = url.substring(url.lastIndexOf('/') + 1);
        if (value.endsWith(".git")) value = value.substring(0, value.length() - 4);
        if (value.isBlank())
            throw new IllegalArgumentException("Cannot determine repository name from " + url);
        return value;
    }

    private static void gitClone(String source, Path target) {
        try {
            var process =
                    new ProcessBuilder("git", "clone", "--depth", "1", source, target.toString())
                            .inheritIO()
                            .start();
            if (process.waitFor() != 0)
                throw new IllegalStateException("git clone failed for " + source);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to run git", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git clone interrupted", e);
        }
    }

    private static void importPackage(
            Path descriptorSource, Path target, PackageDescriptor descriptor) {
        var importOptions = descriptor.importOptions();
        var temporary = createTemporaryDirectory(target);
        try {
            var reference = findReference(importOptions.repo().toString(), descriptor);
            gitClone(importOptions.repo().toString(), reference, temporary);
            copy(temporary, target);
            copyDescriptor(descriptorSource, target);
        } finally {
            delete(temporary);
        }
    }

    private static String findReference(String repository, PackageDescriptor descriptor) {
        var version = descriptor.version().version();
        var candidates =
                List.of(version, "v" + version, descriptor.version().name() + "-" + version);
        for (var candidate : candidates) {
            try {
                var process =
                        new ProcessBuilder(
                                        "git",
                                        "ls-remote",
                                        "--exit-code",
                                        "--refs",
                                        repository,
                                        "refs/tags/" + candidate)
                                .redirectErrorStream(true)
                                .start();
                if (process.waitFor() == 0) return candidate;
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to inspect git tags for " + repository, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Git tag lookup interrupted", e);
            }
        }
        throw new IllegalArgumentException(
                "No upstream tag found for " + descriptor.version() + " in " + repository);
    }

    private static void gitClone(String source, String reference, Path target) {
        try {
            var process =
                    new ProcessBuilder(
                                    "git",
                                    "clone",
                                    "--depth",
                                    "1",
                                    "--branch",
                                    reference,
                                    source,
                                    target.toString())
                            .inheritIO()
                            .start();
            if (process.waitFor() != 0)
                throw new IllegalStateException(
                        "git clone failed for " + source + " at " + reference);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to run git", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git clone interrupted", e);
        }
    }

    private static Path createTemporaryDirectory(Path target) {
        try {
            Files.createDirectories(target.getParent());
            return Files.createTempDirectory(target.getParent(), ".wstool-import-");
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create import directory", e);
        }
    }

    private static void copyDescriptor(Path source, Path target) {
        try {
            var wstool = source.resolve("wstool.pkl");
            var packageFile = source.resolve("Package.pkl");
            if (Files.exists(wstool))
                Files.copy(
                        wstool, target.resolve("wstool.pkl"), StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(packageFile))
                Files.copy(
                        packageFile,
                        target.resolve("Package.pkl"),
                        StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to copy package descriptor", e);
        }
    }

    private static void delete(Path path) {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            item -> {
                                try {
                                    Files.deleteIfExists(item);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to delete import directory", e);
        }
    }

    private static void copy(Path source, Path target) {
        try (var paths = Files.walk(source)) {
            paths.forEach(
                    path -> {
                        var destination = target.resolve(source.relativize(path).toString());
                        try {
                            if (Files.isDirectory(path)) Files.createDirectories(destination);
                            else {
                                Files.createDirectories(destination.getParent());
                                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to clone " + source, e);
        }
    }
}
