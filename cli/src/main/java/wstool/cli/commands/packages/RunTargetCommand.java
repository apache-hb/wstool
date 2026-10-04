package wstool.cli.commands.packages;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import wstool.Package;
import wstool.common.model.PackageMajorVersion;
import wstool.workspace.GitHelper;
import wstool.workspace.LocalWorkspace;
import wstool.workspace.PklHelper;
import wstool.workspace.SysrootManager;

@Command(name = "run", description = "Run a package build target.")
public class RunTargetCommand implements Callable<Integer> {
    @Parameters(arity = "0..*", description = "Target and arguments forwarded to the build tool.")
    List<String> args = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        Path source = Path.of("").toAbsolutePath().normalize();
        try (PklHelper pkl = new PklHelper(Map.of())) {
            GitHelper git = new GitHelper(null, null);
            LocalWorkspace workspace = LocalWorkspace.findUp(git, pkl, source);
            Package config = pkl.evaluatePackage(source.resolve("wstool.pkl"));
            String name = config.getPackage().getName();
            String version = config.getPackage().getVersion();

            var localPackage = workspace.getPackage(name);
            Path prefix = localPackage.prefix();
            Path privateDirectory = prefix.resolve("private");
            Path sysroot = privateDirectory.resolve("sysroot");
            RunCodePackage codePackage =
                    new RunCodePackage(new PackageMajorVersion(name, version), source, prefix);
            new SysrootManager()
                    .createSysroot(
                            sysroot, workspace, codePackage, SysrootManager.SysrootTarget.BUILD);

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
