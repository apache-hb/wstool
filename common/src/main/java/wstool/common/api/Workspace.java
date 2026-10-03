package wstool.common.api;

import java.nio.file.Path;
import java.util.List;

public interface Workspace {
    String name();

    Path root();

    /**
     * Packages that have been cloned and are in the workspace
     *
     * @return
     */
    List<CodePackage> sources();

    /**
     * Packages that have not been cloned and as just build artifacts
     *
     * @return
     */
    List<CodePackage> artifacts();

    void clone(CodePackage pkg);

    void remove(CodePackage pkg);
}
