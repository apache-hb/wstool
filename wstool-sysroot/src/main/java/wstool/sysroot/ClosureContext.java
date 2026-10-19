package wstool.sysroot;

import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;

public record ClosureContext(LocalWorkspace workspace, PackageMajorVersion name) {}
