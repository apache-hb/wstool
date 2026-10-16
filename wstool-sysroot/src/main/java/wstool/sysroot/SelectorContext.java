package wstool.sysroot;

import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;

public record SelectorContext(LocalWorkspace workspace, PackageMajorVersion name) {

}
