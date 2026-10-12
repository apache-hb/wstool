package wstool.simple;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.tool.RunBuildToolInput;

@Singleton
public class BuildToolHelper {
    private static final Logger LOGGER = LogManager.getLogger(BuildToolHelper.class);

    private final PathHelper pathHelper;

    @Inject
    public BuildToolHelper(PathHelper pathHelper) {
        this.pathHelper = pathHelper;
    }

    public int run(RunBuildToolInput input) throws Exception {
        Workspace workspace = input.workspace();

        MaterializedPackage target = workspace.find(input.name());
        Files.createDirectories(target.builddir());

        ProcessBuilder pb = new ProcessBuilder(input.arguments());
        pb.directory(target.source().toFile());
        pb.inheritIO();

        configureWstEnvironment(pb.environment(), target.builddir(), target);
        String path = createBuildToolPath(pb.environment(), workspace, input.name());
        configureBuildToolPath(pb.environment(), path);

        try {
            return pb.start().waitFor();
        } catch (Exception e) {
            LOGGER.warn("Executing {} on ({}) failed", input.arguments(), path, e);
            throw e;
        }
    }

    private void configureWstEnvironment(
            Map<String, String> env, Path builddir, MaterializedPackage target) {
        env.put("WST_PRIVATE", builddir.toAbsolutePath().toString());
        env.put("WST_SRCDIR", target.source().toAbsolutePath().toString());
        env.put("WST_PREFIX", target.prefix().toAbsolutePath().toString());
    }

    private void configureBuildToolPath(Map<String, String> env, String path) throws Exception {
        env.put("PATH", path);
    }

    private String createBuildToolPath(
            Map<String, String> env, Workspace workspace, PackageMajorVersion name)
            throws Exception {
        String path = Objects.requireNonNull(env.get("PATH"));
        String closure = pathHelper.buildClosurePath(workspace, name);
        return closure + ":" + path;
    }
}
