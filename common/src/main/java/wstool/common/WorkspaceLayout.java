package wstool.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;
import wstool.common.model.PackageDescriptor;

public final class WorkspaceLayout {
    public static final String METADATA_DIRECTORY = ".wstool";

    private final Path root;

    private WorkspaceLayout(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static WorkspaceLayout create(Path root) {
        var layout = new WorkspaceLayout(root);
        try {
            Files.createDirectories(layout.metadata());
            Files.createDirectories(layout.sources());
            Files.createDirectories(layout.cache());
            Files.createDirectories(layout.sysroot());
            return layout;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create workspace at " + root, e);
        }
    }

    public static WorkspaceLayout find(Path start) {
        var path = start.toAbsolutePath().normalize();
        while (path != null) {
            if (Files.isDirectory(path.resolve(METADATA_DIRECTORY))) {
                return new WorkspaceLayout(path);
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Not inside a wstool workspace: " + start);
    }

    public Path root() {
        return root;
    }

    public Path metadata() {
        return root.resolve(METADATA_DIRECTORY);
    }

    public Path sources() {
        return root.resolve("src");
    }

    public Path cache() {
        return metadata().resolve("cache");
    }

    public Path sysroot() {
        return metadata().resolve("sysroot");
    }

    public Path buildDirectory(PackageDescriptor descriptor) {
        return metadata().resolve("build").resolve(descriptor.version().name());
    }

    public void ensureBuildDirectoryLink(Path source, PackageDescriptor descriptor) {
        var link = source.resolve("builddir");
        var buildDirectory = buildDirectory(descriptor);
        try {
            Files.createDirectories(buildDirectory);
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(link)
                        && Files.readSymbolicLink(link)
                                .equals(link.getParent().relativize(buildDirectory))) return;
                throw new IllegalStateException("Package already contains " + link);
            }
            Files.createSymbolicLink(link, link.getParent().relativize(buildDirectory));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to link package build directory " + link, e);
        }
    }

    public Path stageDirectory(PackageDescriptor descriptor) {
        return metadata().resolve("stage").resolve(descriptor.version().name());
    }

    public Path stateFile() {
        return metadata().resolve("workspace.properties");
    }

    public Properties state() {
        var properties = new Properties();
        if (Files.exists(stateFile())) {
            try (var input = Files.newInputStream(stateFile())) {
                properties.load(input);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read " + stateFile(), e);
            }
        }
        return properties;
    }

    public void saveState(Properties properties) {
        try (var output = Files.newOutputStream(stateFile())) {
            properties.store(output, "wstool workspace");
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + stateFile(), e);
        }
    }
}
