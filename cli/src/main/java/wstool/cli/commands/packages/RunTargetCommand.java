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
import wstool.simple.MaterializedPackage;
import wstool.simple.BuildToolHelper;
import wstool.simple.GitHelper;
import wstool.simple.LocalPackage;
import wstool.simple.PackageFactory;
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
        try (PklHelper pkl = new PklHelper(null)) {
            PathHelper pathHelper = new PathHelper();
            WorkspaceFactory workspaceFactory = new WorkspaceFactory(pkl);
            PackageFactory packageFactory = new PackageFactory(workspaceFactory, pkl);
            WorkspaceHelper workspaceHelper = CommandHelpers.workspace(new GitHelper(false), new StorageHelper(), pkl);
            Path workspaceRoot = workspaceHelper.findWorkspaceUp(Path.of(""));
            MaterializedPackage current = packageFactory.findUp(Path.of(""));
            BuildToolHelper buildToolHelper = new BuildToolHelper(pathHelper);

            List<String> command = new ArrayList<>();
            if (current instanceof LocalPackage local) {
                command.add(local.buildTool());
            }
            command.addAll(args);

            try (Workspace buildWorkspace = workspaceFactory.open(workspaceRoot)) {
                workspaceHelper.refresh(buildWorkspace);

                return buildToolHelper.run(
                        ImmutableRunBuildToolInput.builder()
                                .workspace(buildWorkspace)
                                .name(current.name())
                                .arguments(command)
                                .build());
            }
        }
    }
}
