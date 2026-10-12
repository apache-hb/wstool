package wstool.simple;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;

@Singleton
public class SysrootManager {
    private final DependencyResolver dependencyResolver;

    @Inject
    public SysrootManager(DependencyResolver dependencyResolver) {
        this.dependencyResolver = dependencyResolver;
    }

    public enum SysrootTarget {
        BUILD,
        DEVELOPMENT,
        TEST_DEPENDENCIES,
        RUNTIME_DEPENDENCIES
    }

    /** Creates or updates a sysroot from the selected package closure. */
    public Path createSysroot(
            Path output, Path workspaceRoot, String packageName, SysrootTarget target) {
        return dependencyResolver.createClosure(
                workspaceRoot,
                packageName,
                target.name().toLowerCase().replace("_dependencies", ""),
                output);
    }
}
