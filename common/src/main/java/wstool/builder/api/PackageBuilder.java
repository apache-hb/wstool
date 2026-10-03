package wstool.builder.api;

import java.util.List;
import wstool.common.api.CodePackage;
import wstool.common.api.Workspace;

public interface PackageBuilder {
    void runBuildTarget(Workspace workspace, CodePackage pkg, List<String> cmd);
}
