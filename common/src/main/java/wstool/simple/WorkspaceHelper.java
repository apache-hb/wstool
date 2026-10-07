package wstool.simple;

import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import wstool.Distribution;
import wstool.Distribution.GitRepository;
import wstool.Distribution.PackageSource;
import wstool.simple.git.GitCloneInput;
import wstool.simple.git.GitRepositoryLocation;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.workspace.AddPackageToWorkspaceInput;
import wstool.simple.workspace.CreateWorkspaceInput;

public class WorkspaceHelper {
    private static final String CONFIG_FILE = "wstool.db";
    private static final String DISTRIBUTION = "distribution";

    private final GitHelper git;
    private final StorageHelper storage;
    private final DistributionHelper distribution;

    public WorkspaceHelper(GitHelper git, StorageHelper storage, DistributionHelper distribution) {
        this.git = git;
        this.storage = storage;
        this.distribution = distribution;
    }

    public Path workspaceSourceFolder(Path workspace) {
        return workspace.resolve("src");
    }

    public Path workspacePackageFolder(Path workspace, String packageName) {
        return workspaceSourceFolder(workspace).resolve(packageName);
    }

    public Path workspacePrefixFolder(Path workspace, String packageName) {
        return workspace.resolve("private/prefix").resolve(packageName);
    }

    public Path workspaceBuildFolder(Path workspace, String packageName) {
        return workspace.resolve("private/build").resolve(packageName);
    }

    public Path workspaceCacheFolder(Path workspace) {
        return workspace.resolve("private/cache");
    }

    public Path workspaceCacheFolder(Path workspace, String packageName) {
        return workspaceCacheFolder(workspace).resolve(packageName);
    }

    public Path workspaceDistributionFolder(Path workspace) {
        return workspace.resolve(DISTRIBUTION);
    }

    public Path workspaceDistributionConfig(Path workspace) {
        return workspaceDistributionFolder(workspace).resolve("distribution.pkl");
    }

    public boolean hasDistributionConfig(Path workspace) {
        return Files.exists(workspaceDistributionConfig(workspace));
    }

    public void createWorkspace(CreateWorkspaceInput input) throws Exception {
        Path workspace = input.parent().resolve(input.name());

        Files.createDirectories(workspace);

        // Folder where locally cloned packages are placed
        Files.createDirectories(workspaceSourceFolder(workspace));

        Files.createDirectories(workspace.resolve("private"));

        // Folder where remote artifacts are cached
        Files.createDirectories(workspaceCacheFolder(workspace));

        // Locally built artifacts
        Files.createDirectories(workspace.resolve("private/prefix"));

        // Intermediate build directories
        Files.createDirectories(workspace.resolve("private/build"));

        // The distribution config
        GitCloneInput clone =
                ImmutableGitCloneInput.builder()
                        .parent(workspace)
                        .name(DISTRIBUTION)
                        .repository(input.distribution())
                        .build();

        git.clone(clone);

        storage.createWorkspaceStorage(workspaceConfig(workspace));
    }

    public void addPackageToWorkspace(AddPackageToWorkspaceInput input) throws Exception {
        Distribution distroConfig =
                distribution.getDistributionConfig(workspaceDistributionConfig(input.workspace()));
        PackageSource source =
                distribution.findPackageMajorVersion(distroConfig, input.name(), input.version());
        switch (source) {
            case GitRepository repo -> cloneGitPackage(repo, input.workspace());
            default ->
                    throw new IllegalArgumentException(
                            String.format(
                                    "Unsupported package source type '%s' for package '%s'.",
                                    source.getClass().getSimpleName(), input.name()));
        }

        storage.addPackageToWorkspaceStorage(
                workspaceConfig(input.workspace()),
                source.getName(),
                source.getVersion(),
                workspacePackageFolder(input.workspace(), input.name()).toString(),
                workspacePrefixFolder(input.workspace(), input.name()).toString(),
                workspaceBuildFolder(input.workspace(), input.name()).toString(),
                true);
    }

    public List<LocalPackage> sourcePackages(Path workspace) {
        return packages(workspace, true);
    }

    public List<LocalPackage> artifactPackages(Path workspace) {
        return packages(workspace, false);
    }

    private List<LocalPackage> packages(Path workspace, boolean cloned) {
        try (WorkspaceStorage workspaceStorage =
                new WorkspaceStorage(workspaceConfig(workspace))) {
            return workspaceStorage.getPackages(cloned);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to read workspace packages from " + workspace, e);
        }
    }

    public Path findWorkspaceUp(Path path) {
        Path current = path.toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(CONFIG_FILE))) {
            current = current.getParent();
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "No workspace found in current directory or any parent directories.");
        }

        return current;
    }

    Path workspaceConfig(Path workspace) {
        return workspace.resolve(CONFIG_FILE);
    }

    @VisibleForTesting
    void cloneGitPackage(GitRepository source, Path workspace) throws Exception {
        GitRepositoryLocation location =
                ImmutableGitRepositoryLocation.builder()
                        .repository(source.getRepo())
                        .branch(source.getBranch())
                        .build();

        GitCloneInput clone =
                ImmutableGitCloneInput.builder()
                        .parent(workspaceSourceFolder(workspace))
                        .name(source.getName())
                        .repository(location)
                        .build();

        git.clone(clone);
    }
}
