package wstool.simple;

import wstool.simple.packages.RunTargetInput;

public class BuildHelper {
    private final PackageHelper packageHelper;

    public BuildHelper(PackageHelper packageHelper) {
        this.packageHelper = packageHelper;
    }

    public void runTarget(RunTargetInput input) {}
}
