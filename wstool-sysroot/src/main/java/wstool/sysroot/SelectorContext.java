package wstool.sysroot;

import java.util.Objects;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;

public record SelectorContext(LocalWorkspace workspace, PackageMajorVersion name) {
    public SelectorContext {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
