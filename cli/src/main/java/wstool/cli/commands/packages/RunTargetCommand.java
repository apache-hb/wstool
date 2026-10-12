package wstool.cli.commands.packages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Unmatched;
import wstool.Package;
import wstool.cli.VersionProvider;
import wstool.cli.commands.CommandHelpers;
import wstool.simple.BuildToolHelper;
import wstool.simple.GitHelper;
import wstool.simple.PackageHelper;
import wstool.simple.PathHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.Workspace;
import wstool.simple.WorkspaceFactory;
import wstool.simple.WorkspaceHelper;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.tool.ImmutableRunBuildToolInput;

@Command(
        name = "run",
        description = "Run a package build target.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
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
            PathHelper pathHelper = new PathHelper();
            WorkspaceFactory workspaceFactory = new WorkspaceFactory(pkl);
            WorkspaceHelper workspace =
                    CommandHelpers.workspace(new GitHelper(false), new StorageHelper(), pkl);
            Path workspaceRoot = workspace.findWorkspaceUp(source);
            Package config = new PackageHelper(pkl).getPackageConfig(source.resolve("wstool.pkl"));
            String name = config.getPackage().getName();
            PackageMajorVersion version =
                    new PackageMajorVersion(name, config.getPackage().getVersion());
            BuildToolHelper buildToolHelper = new BuildToolHelper(pathHelper, workspace);

            boolean isWorkspacePackage =
                    workspace.sourcePackages(workspaceRoot).stream()
                            .anyMatch(pkg -> pkg.version().equals(version));
            if (!isWorkspacePackage) {
                throw new IllegalArgumentException("Package is not in the workspace: " + version);
            }

            Path privateDirectory = workspace.workspaceBuildFolder(workspaceRoot, name);
            Files.createDirectories(privateDirectory);

            List<String> command = new ArrayList<>();
            command.add(config.getBuildOptions().getTool());
            command.addAll(args);
            try (Workspace buildWorkspace = workspaceFactory.open(workspaceRoot)) {
                workspace.refresh(buildWorkspace);

                return buildToolHelper.run(
                        ImmutableRunBuildToolInput.builder()
                                .workspace(buildWorkspace)
                                .name(version)
                                .arguments(command)
                                .build());
            }
        }
    }
}
