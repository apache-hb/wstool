package wstool.cli.commands.packages;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.Package;
import wstool.simple.DistributionHelper;
import wstool.simple.GitHelper;
import wstool.simple.LocalPackage;
import wstool.simple.PackageHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.SysrootManager;
import wstool.simple.WorkspaceHelper;
import wstool.simple.model.PackageMajorVersion;

@Command(name = "run", description = "Run a package build target.")
public class RunTargetCommand implements Callable<Integer> {
    @Unmatched List<String> args = new ArrayList<>();

    public RunTargetCommand() {}

    RunTargetCommand(List<String> args) {
        this.args = new ArrayList<>(args);
    }

    @Override
    public Integer call() throws Exception {
        Path source = Path.of("").toAbsolutePath().normalize();
        try (PklHelper pkl = new PklHelper(null)) {
            WorkspaceHelper workspace =
                    new WorkspaceHelper(
                            new GitHelper(false), new StorageHelper(), new DistributionHelper(pkl));
            Path workspaceRoot = workspace.findWorkspaceUp(source);
            Package config = new PackageHelper(pkl).getPackageConfig(source.resolve("wstool.pkl"));
            String name = config.getPackage().getName();
            PackageMajorVersion version =
                    new PackageMajorVersion(name, config.getPackage().getVersion());

            LocalPackage localPackage =
                    workspace.sourcePackages(workspaceRoot).stream()
                            .filter(pkg -> pkg.version().equals(version))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Package is not in the workspace: " + version));
            Path prefix = localPackage.prefix();
            Path privateDirectory = workspace.workspaceBuildFolder(workspaceRoot, name);
            Path sysroot = privateDirectory.resolve("sysroot");
            new SysrootManager()
                    .createSysroot(
                            sysroot,
                            workspace,
                            workspaceRoot,
                            name,
                            SysrootManager.SysrootTarget.BUILD);

            String tool = config.getBuildOptions().getTool();
            Path executable = sysroot.resolve("bin").resolve(tool);
            Path localTool = source.resolve("wstool").resolve("bin").resolve(tool);
            if (!Files.isExecutable(executable) && Files.isExecutable(localTool)) {
                executable = localTool;
            }
            if (!Files.isExecutable(executable)) {
                throw new IllegalStateException(
                        "Build tool '"
                                + tool
                                + "' was not found in the build sysroot or "
                                + localTool);
            }

            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(args);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(source.toFile());
            processBuilder.inheritIO();
            Map<String, String> environment = processBuilder.environment();
            environment.put("WST_SRCDIR", source.toString());
            environment.put("WST_PRIVATE", privateDirectory.toString());
            environment.put("WST_PREFIX", prefix.toString());
            environment.put("WST_SYSROOT", sysroot.toString());
            String path = environment.getOrDefault("PATH", "");
            environment.put("PATH", sysroot.resolve("bin") + File.pathSeparator + path);
            return processBuilder.start().waitFor();
        }
    }
}
