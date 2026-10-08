package wstool.simple;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import wstool.Package;
import wstool.simple.packages.RunTargetInput;

public class BuildHelper {
    private final PackageHelper packageHelper;

    public BuildHelper(PackageHelper packageHelper) {
        this.packageHelper = packageHelper;
    }

    public void runTarget(RunTargetInput input) {
        Path source = input._package().toAbsolutePath().normalize();
        Path workspace = input.workspace().toAbsolutePath().normalize();
        Package config = packageHelper.getPackageConfig(source.resolve("wstool.pkl"));
        String packageName = config.getPackage().getName();
        Path prefix = workspace.resolve("private/prefix").resolve(packageName);
        Path privateDirectory = workspace.resolve("private/build").resolve(packageName);

        List<String> command = new ArrayList<>();
        command.add(config.getBuildOptions().getTool());
        command.addAll(input.args());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(source.toFile());
        processBuilder.inheritIO();
        var environment = processBuilder.environment();
        environment.put("WST_SRCDIR", source.toString());
        environment.put("WST_PRIVATE", privateDirectory.toString());
        environment.put("WST_PREFIX", prefix.toString());
        String path = environment.getOrDefault("PATH", "");
        environment.put("PATH", prefix.resolve("bin") + File.pathSeparator + path);

        try {
            int status = processBuilder.start().waitFor();
            if (status != 0) {
                throw new IllegalStateException("Build target failed with status " + status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while running build target: " + command, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to run build target: " + command, e);
        }
    }
}
