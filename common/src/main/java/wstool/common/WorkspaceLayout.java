package wstool.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import wstool.common.model.DistributionDescriptor;
import wstool.common.model.PackageDescriptor;

public final class WorkspaceLayout {
    public static final String METADATA_DIRECTORY = ".wstool";
    public static final String DISTRIBUTION_DIRECTORY = "distribution";
    public static final String WORLD_FILE = "world.pkl";

    private final Path root;

    private WorkspaceLayout(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static WorkspaceLayout create(Path root) {
        var layout = new WorkspaceLayout(root);
        try {
            Files.createDirectories(layout.metadata());
            Files.createDirectories(layout.distributionDirectory());
            Files.createDirectories(layout.sources());
            Files.createDirectories(layout.cache());
            Files.createDirectories(layout.sysroot());
            Files.createSymbolicLink(
                    layout.root.resolve(WORLD_FILE), Path.of("distribution", WORLD_FILE));
            return layout;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create workspace at " + root, e);
        }
    }

    public static WorkspaceLayout find(Path start) {
        var path = start.toAbsolutePath().normalize();
        while (path != null) {
            var world = path.resolve(WORLD_FILE);
            if (Files.isRegularFile(world)
                    || (Files.isSymbolicLink(world)
                            && Files.isDirectory(path.resolve(METADATA_DIRECTORY)))) {
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

    public Path distributionDirectory() {
        return root.resolve(DISTRIBUTION_DIRECTORY);
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

    public Path worldFile() {
        try {
            return root.resolve(WORLD_FILE).toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to resolve workspace world file", e);
        }
    }

    public DistributionDescriptor distribution() {
        return DistributionReader.read(worldFile());
    }
}
