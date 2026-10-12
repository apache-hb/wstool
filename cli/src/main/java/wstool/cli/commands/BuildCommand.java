package wstool.cli.commands;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import wstool.Distribution;
import wstool.cli.VersionProvider;
import wstool.simple.ArchiveHelper;
import wstool.simple.CacheHelper;
import wstool.simple.ContainerBuildHelper;
import wstool.simple.DistributionHelper;
import wstool.simple.GitHelper;
import wstool.simple.PackageArtifactCache;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.WorkspaceHelper;
import wstool.simple.container.ImmutableRunContainerBuildInput;
import wstool.simple.model.PackageMajorVersion;

@Command(
        name = "build",
        description = "Build a workspace package in a container.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class BuildCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "target", description = "Package name or name@version.")
    private String target;

    @Override
    public Integer call() throws Exception {
        try (PklHelper pkl = new PklHelper(null)) {
            GitHelper git = new GitHelper(false);
            StorageHelper storage = new StorageHelper();
            WorkspaceHelper workspace = CommandHelpers.workspace(git, storage, pkl);
            Path root = workspace.findWorkspaceUp(Path.of(""));
            DistributionHelper distributionHelper = new DistributionHelper(pkl);
            Distribution distribution =
                    distributionHelper.getDistributionConfig(
                            workspace.workspaceDistributionConfig(root));
            PackageMajorVersion version = parseTarget(target, workspace, root);
            URI endpoint = distribution.getStorage().getEndpoint();
            Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
            var builder =
                    S3Client.builder()
                            .region(region)
                            .credentialsProvider(DefaultCredentialsProvider.builder().build());
            if (endpoint != null) {
                builder.endpointOverride(endpoint)
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(true)
                                        .chunkedEncodingEnabled(false)
                                        .build());
            }
            try (S3Client s3 = builder.build()) {
                ContainerBuildHelper build =
                        new ContainerBuildHelper(
                                new PackageArtifactCache(
                                        new CacheHelper(s3, new ArchiveHelper()),
                                        distribution.getStorage().getBucket(),
                                        distribution.getName()),
                                workspace,
                                CommandHelpers.dependencies(git, pkl),
                                pkl,
                                distribution,
                                storage);
                build.runContainerBuild(
                        ImmutableRunContainerBuildInput.builder()
                                .workspace(root)
                                .name(version)
                                .build());
            }
            return 0;
        }
    }

    private PackageMajorVersion parseTarget(String value, WorkspaceHelper workspace, Path root) {
        int at = value.lastIndexOf('@');
        String name = at < 0 ? value : value.substring(0, at);
        String version = at < 0 ? "" : value.substring(at + 1);
        if (name.isBlank() || (at >= 0 && version.isBlank())) {
            throw new IllegalArgumentException("Expected target in the form name or name@version.");
        }
        var matches =
                workspace.sourcePackages(root).stream()
                        .map(pkg -> pkg.name())
                        .filter(pkg -> pkg.name().equals(name))
                        .filter(pkg -> version.isEmpty() || pkg.version().equals(version))
                        .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Package not found in workspace: " + value);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Multiple package versions found; specify name@version.");
        }
        return matches.getFirst();
    }
}
