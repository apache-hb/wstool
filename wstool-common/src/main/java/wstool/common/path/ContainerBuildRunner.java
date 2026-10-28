package wstool.common.path;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageConfig;
import wstool.common.workspace.LocalWorkspace;

@Singleton
public class ContainerBuildRunner {
    private final PathBuilder pathBuilder;
    private final BuildSourcePreparer buildSourcePreparer;
    private final DockerBuildExecutor dockerBuildExecutor;

    @Inject
    public ContainerBuildRunner(
            PathBuilder pathBuilder,
            BuildSourcePreparer buildSourcePreparer,
            DockerBuildExecutor dockerBuildExecutor) {
        this.pathBuilder = pathBuilder;
        this.buildSourcePreparer = buildSourcePreparer;
        this.dockerBuildExecutor = dockerBuildExecutor;
    }

    public int runBuild(LocalWorkspace workspace, PackageConfig pkg, List<String> args)
            throws Exception {
        PackageMajorVersion name = pkg.name();
        Path workspacePath = workspace.path().toAbsolutePath().normalize();
        Path source = buildSourcePreparer.prepare(workspace, pkg).toAbsolutePath().normalize();
        Path relativeSource = workspacePath.relativize(source);
        if (relativeSource.isAbsolute() || relativeSource.startsWith("..")) {
            throw new IllegalArgumentException(
                    "Package source must be inside the workspace to build in a container: "
                            + source);
        }

        Files.createDirectories(workspace.builddir(name));
        Files.createDirectories(workspace.prefix(name));

        // Resolve build dependencies on the host so network-isolated containers can use them.
        pathBuilder.prepareBuildDependencies(workspace, name);
        return dockerBuildExecutor.run(workspace, pkg, source, args);
    }
}
