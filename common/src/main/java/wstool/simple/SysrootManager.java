package wstool.simple;

import java.nio.file.Path;

public class SysrootManager {
    public enum SysrootTarget {
        BUILD,
        DEVELOPMENT,
        TEST_DEPENDENCIES,
        RUNTIME_DEPENDENCIES
    }

    /** Creates or updates a sysroot from the selected package closure. */
    public Path createSysroot(
            Path output,
            WorkspaceHelper workspace,
            Path workspaceRoot,
            String packageName,
            SysrootTarget target) {
        return new DependencyResolver()
                .createClosure(
                        workspace,
                        workspaceRoot,
                        packageName,
                        target.name().toLowerCase().replace("_dependencies", ""),
                        output);
    }
}
