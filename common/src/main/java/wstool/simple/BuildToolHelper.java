package wstool.simple;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import wstool.simple.model.PackageMajorVersion;
import wstool.simple.tool.RunBuildToolInput;

public class BuildToolHelper {
    private final PathHelper pathHelper;
    private final WorkspaceHelper workspaceHelper;

    public BuildToolHelper(PathHelper pathHelper, WorkspaceHelper workspaceHelper) {
        this.pathHelper = pathHelper;
        this.workspaceHelper = workspaceHelper;
    }

    public int run(RunBuildToolInput input) throws Exception {
        Workspace workspace = input.workspace();

        LocalPackage target = workspace.storage().find(input.name());

        ProcessBuilder pb = new ProcessBuilder(input.arguments());
        pb.directory(target.source().toFile());
        pb.inheritIO();

        Path builddir = workspaceHelper.workspaceBuildFolder(workspace.path(), input.name().name());

        configureWstEnvironment(pb.environment(), builddir, target);
        configureBuildToolPath(pb.environment(), input.workspace(), input.name());

        return pb.start().waitFor();
    }

    private void configureWstEnvironment(Map<String, String> env, Path builddir, LocalPackage target) {
        env.put("WST_PRIVATE", builddir.toAbsolutePath().toString());
        env.put("WST_SRCDIR", target.source().toAbsolutePath().toString());
        env.put("WST_PREFIX", target.prefix().toAbsolutePath().toString());
    }

    private void configureBuildToolPath(Map<String, String> env, Workspace workspace, PackageMajorVersion name) throws Exception {
        String path = Objects.requireNonNull(env.get("PATH"));
        String closure = pathHelper.buildClosurePath(workspace, name);
        String buildToolPath = closure + ":" + path;
        env.put("PATH", buildToolPath);
    }
}
