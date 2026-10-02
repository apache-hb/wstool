package wstool.builder.impl;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import wstool.builder.api.PackageBuilder;
import wstool.common.api.CodePackage;
import wstool.common.api.Workspace;

@Singleton
public class PackageBuilderImpl implements PackageBuilder {
    @Inject
    public PackageBuilderImpl() {

    }

    @Override
    public void runBuildTarget(Workspace workspace, CodePackage pkg, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd.toArray(new String[0]));
    }
}
