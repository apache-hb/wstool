package wstool.common.path;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageConfig;
import wstool.common.workspace.LocalWorkspace;

@Singleton
public class BuildRunner {
    private static final Logger LOGGER = LogManager.getLogger(BuildRunner.class);

    private final PathBuilder pathBuilder;

    @Inject
    public BuildRunner(PathBuilder pathBuilder) {
        this.pathBuilder = pathBuilder;
    }

    public int runBuild(LocalWorkspace workspace, PackageConfig pkg, List<String> args)
            throws Exception {

        PackageMajorVersion name = pkg.name();
        Path builddir = workspace.builddir(name);
        Path source = workspace.source(name);
        Path prefix = workspace.prefix(name);
        Files.createDirectories(builddir);
        Files.createDirectories(prefix);

        String path = pathBuilder.buildToolPath(workspace, name);

        List<String> command = new ArrayList<>();
        command.add("/usr/bin/env");
        command.add("--");
        command.add(pkg.buildTool());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        String buildPath = path + ":" + pb.environment().get("PATH");
        LOGGER.info(
                "Running build for package {} with command: {} in directory: {} with PATH: {}",
                name,
                command,
                builddir,
                buildPath);
        pb.directory(builddir.toFile());
        pb.environment().put("PATH", buildPath);
        pb.environment().put("WST_PRIVATE", builddir.toAbsolutePath().toString());
        pb.environment().put("WST_SRCDIR", source.toAbsolutePath().toString());
        pb.environment().put("WST_PREFIX", prefix.toAbsolutePath().toString());
        pb.inheritIO();

        Process process = pb.start();
        return process.waitFor();
    }
}
