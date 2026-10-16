package wstool.sysroot;

import java.nio.file.Path;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;

public record SelectorContext(LocalWorkspace workspace, PackageMajorVersion name, Path output) {
    public SelectorContext(LocalWorkspace workspace, PackageMajorVersion name) {
        this(workspace, name, null);
    }

}
