package wstool.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wstool.common.WorkspaceLayout;

@Command(
        name = "clone",
        description = "Clone a local package repository into the workspace.",
        mixinStandardHelpOptions = true)
public final class WorkspaceCloneCommand implements Callable<Integer> {
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
                copy(
                        Path.of(source).toAbsolutePath().normalize(),
                        layout.sources().resolve(targetName));
            }
            System.out.println("Cloned " + targetName);
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
