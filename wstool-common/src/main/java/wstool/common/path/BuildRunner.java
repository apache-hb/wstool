package wstool.common.path;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageConfig;
import wstool.common.workspace.LocalWorkspace;

@Singleton
public class BuildRunner {
    private static final Logger LOGGER = LogManager.getLogger(BuildRunner.class);

    private final PathBuilder pathBuilder;
    private final BuildSourcePreparer buildSourcePreparer;

    @Inject
    public BuildRunner(PathBuilder pathBuilder, BuildSourcePreparer buildSourcePreparer) {
        this.pathBuilder = pathBuilder;
        this.buildSourcePreparer = buildSourcePreparer;
    }

    public int runBuild(LocalWorkspace workspace, PackageConfig pkg, List<String> args)
            throws Exception {
        Path source = buildSourcePreparer.prepare(workspace, pkg);
        return runBuildAtSource(workspace, pkg, args, source);
    }

    public int runBuild(
            LocalWorkspace workspace, PackageConfig pkg, Path packageDirectory, List<String> args)
            throws Exception {
        Path preparedSource = workspace.buildSource(pkg.name()).toAbsolutePath().normalize();
        Path packagePath = packageDirectory.toAbsolutePath().normalize();
        Path source =
                packagePath.startsWith(preparedSource)
                        ? packagePath
                        : buildSourcePreparer.prepare(workspace, pkg);
        return runBuildAtSource(workspace, pkg, args, source);
    }

    private int runBuildAtSource(
            LocalWorkspace workspace, PackageConfig pkg, List<String> args, Path source)
            throws Exception {
        PackageMajorVersion name = pkg.name();
        Path builddir = workspace.builddir(name);
        Path prefix = workspace.prefix(name);
        Files.createDirectories(builddir);
        Files.createDirectories(prefix);

        String path = pathBuilder.buildToolPath(workspace, name, source);

        List<String> command = new ArrayList<>();
        command.add("/usr/bin/env");
        command.add("--");
        command.add(pkg.buildTool());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        String buildPath = path + ":" + pb.environment().get("PATH");
        String libraryPath = buildLibraryPath(workspace.path().resolve("private/prefix"));
        LOGGER.info(
                "Running build for package {} with command: {} in directory: {} with PATH: {}",
                name,
                command,
                source,
                buildPath);
        pb.directory(source.toFile());
        pb.environment().put("PATH", buildPath);
        if (!libraryPath.isBlank()) {
            String existingLibraryPath = pb.environment().get("LD_LIBRARY_PATH");
            pb.environment()
                    .put(
                            "LD_LIBRARY_PATH",
                            existingLibraryPath == null || existingLibraryPath.isBlank()
                                    ? libraryPath
                                    : libraryPath + ":" + existingLibraryPath);
        }
        pb.environment().put("WST_PRIVATE", builddir.toAbsolutePath().toString());
        pb.environment().put("WST_SRCDIR", source.toAbsolutePath().toString());
        pb.environment().put("WST_PREFIX", prefix.toAbsolutePath().toString());
        pb.inheritIO();

        Process process = pb.start();
        return process.waitFor();
    }

    private String buildLibraryPath(Path prefixRoot) throws Exception {
        List<Path> roots = new ArrayList<>();
        for (Path packageDirectory : childDirectories(prefixRoot)) {
            roots.addAll(childDirectories(packageDirectory));
        }
        List<String> libraries = new ArrayList<>();
        for (Path root : roots) {
            for (Path libraryRoot :
                    List.of(
                            root.resolve("lib"),
                            root.resolve("lib64"),
                            root.resolve("usr/lib"),
                            root.resolve("usr/lib64"))) {
                if (!isDirectory(libraryRoot)) {
                    continue;
                }
                libraries.add(libraryRoot.toString());
                libraries.addAll(
                        childDirectories(libraryRoot).stream().map(Path::toString).toList());
            }
        }
        return libraries.stream().distinct().collect(Collectors.joining(":"));
    }

    private List<Path> childDirectories(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.filter(this::isDirectory).sorted().toList();
        } catch (NoSuchFileException exception) {
            return List.of();
        }
    }

    private boolean isDirectory(Path directory) {
        try {
            return Files.readAttributes(directory, BasicFileAttributes.class).isDirectory();
        } catch (NoSuchFileException exception) {
            return false;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect directory " + directory, exception);
        }
    }
}
