package wstool.simple;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import wstool.Distribution;
import wstool.Distribution.Folder;
import wstool.Distribution.GitRepository;
import wstool.Distribution.PackageSource;
import wstool.Package;
import wstool.Package.ImportOptions;
import wstool.simple.git.GitCloneInput;
import wstool.simple.git.GitRepositoryLocation;
import wstool.simple.git.ImmutableGitCloneInput;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.workspace.AddPackageToWorkspaceInput;
import wstool.simple.workspace.CreateWorkspaceInput;

public class WorkspaceHelper {
    private static final String CONFIG_FILE = "wstool.db";
    private static final String DISTRIBUTION = "distribution";

    private final GitHelper git;
    private final StorageHelper storage;
    private final DistributionHelper distribution;
    private final PackageHelper packages;
    private final PackageSourceHelper sources;
    private final ExternalPackageSourceHelper externalSources;

    public WorkspaceHelper(
            GitHelper git,
            StorageHelper storage,
            DistributionHelper distribution,
            PackageHelper packages,
            PackageSourceHelper sources,
            ExternalPackageSourceHelper externalSources) {
        this.git = git;
        this.storage = storage;
        this.distribution = distribution;
        this.packages = packages;
        this.sources = sources;
        this.externalSources = externalSources;
    }

    public Path workspaceSourceFolder(Path workspace) {
        return workspace.resolve("src");
    }

    public Path workspacePackageFolder(Path workspace, String packageName) {
        return workspaceSourceFolder(workspace).resolve(packageName);
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

        storage.createWorkspaceStorage(workspaceConfig(workspace));

        if (input.distribution() != null) {
            setupDistribution(workspace, input.distribution());
        }
    }

    public void setup(Workspace workspace, GitRepositoryLocation repository) throws Exception {
        GitCloneInput clone =
                ImmutableGitCloneInput.builder()
                        .parent(workspace.path())
                        .name(DISTRIBUTION)
                        .repository(repository)
                        .build();
        git.clone(clone);
    }

    public void setupDistribution(Path workspace, GitRepositoryLocation repository)
            throws Exception {
        if (!Files.exists(workspaceConfig(workspace))) {
            throw new IllegalArgumentException("Not a workspace: " + workspace);
        }

        GitCloneInput clone =
                ImmutableGitCloneInput.builder()
                        .parent(workspace)
                        .name(DISTRIBUTION)
                        .repository(repository)
                        .build();
        git.clone(clone);
    }

    public void refresh(Workspace workspace) throws Exception {
        Distribution config =
                distribution.getDistributionConfig(workspaceDistributionConfig(workspace.path()));
        workspace.refresh(config);
    }

    public Distribution refreshDistribution(Path workspace) throws Exception {
        if (!Files.exists(workspaceConfig(workspace))) {
            throw new IllegalArgumentException("Not a workspace: " + workspace);
        }

        Distribution config =
                distribution.getDistributionConfig(workspaceDistributionConfig(workspace));
        storage.refreshDistributionPackages(
                workspaceConfig(workspace),
                workspace,
                config.getPackages().stream()
                        .map(pkg -> new PackageMajorVersion(pkg.getName(), pkg.getVersion()))
                        .toList());
        return config;
    }

    public void addPackageToWorkspace(AddPackageToWorkspaceInput input) throws Exception {
        addPackageToWorkspace(input, refreshDistribution(input.workspace()));
    }

    public void addPackageToWorkspace(AddPackageToWorkspaceInput input, Distribution distroConfig)
            throws Exception {
        PackageSource source =
                distribution.findPackageMajorVersion(distroConfig, input.name(), input.version());
        switch (source) {
            case GitRepository repo -> cloneGitPackage(repo, input.workspace());
            case Folder nested -> {
                Path sourceDirectory = sources.materialize(nested, input.workspace());
                sources.copy(
                        sourceDirectory, workspacePackageFolder(input.workspace(), input.name()));
            }
            default ->
                    throw new IllegalArgumentException(
                            String.format(
                                    "Unsupported package source type '%s' for package '%s'.",
                                    source.getClass().getSimpleName(), input.name()));
        }

        Path packageDirectory = workspacePackageFolder(input.workspace(), input.name());
        Path packageConfig = packageDirectory.resolve("wstool.pkl");
        if (packages != null && Files.isRegularFile(packageConfig)) {
            Package packageDefinition = packages.getPackageConfig(packageConfig);
            ImportOptions importOptions = packageDefinition.getImportOptions();
            if (importOptions != null) {
                Path externalSource =
                        externalSources.materialize(
                                importOptions.getSource(),
                                source.getName(),
                                source.getVersion(),
                                input.workspace());
                Path localOverlay =
                        input.workspace()
                                .resolve("private/cache/import-overlays")
                                .resolve(source.getName())
                                .resolve(source.getVersion());
                sources.copy(packageDirectory, localOverlay);
                try {
                    externalSources.mergeAndPatch(
                            externalSource,
                            localOverlay,
                            packageDirectory,
                            importOptions.getPatchContents());
                } finally {
                    deleteTree(localOverlay);
                }
            }
        }

        storage.addPackageToWorkspaceStorage(
                workspaceConfig(input.workspace()),
                source.getName(),
                source.getVersion(),
                workspacePackageFolder(input.workspace(), input.name()).toString(),
                true);
    }

    public void removePackageFromWorkspace(Path workspace, String packageName) throws Exception {
        Path sourceFolder = packageFolder(workspaceSourceFolder(workspace), packageName);
        Path prefixFolder = packageFolder(workspace.resolve("private/prefix"), packageName);
        Path buildFolder = packageFolder(workspace.resolve("private/build"), packageName);

        List<PackageMajorVersion> removed =
                storage.removeClonedPackagesFromWorkspaceStorage(
                        workspaceConfig(workspace), packageName);
        if (removed.isEmpty()) {
            throw new IllegalArgumentException(
                    "No cloned package named '" + packageName + "' is present in the workspace.");
        }

        deleteTree(sourceFolder);
        deleteTree(prefixFolder);
        deleteTree(buildFolder);
    }

    public List<LocalPackage> sourcePackages(Path workspace) {
        return packages(workspace, true);
    }

    public List<LocalPackage> artifactPackages(Path workspace) {
        Path packageCatalog =
                workspace.resolve("private/cache/package-catalog").toAbsolutePath().normalize();
        return packages(workspace, false).stream()
                .filter(
                        pkg ->
                                !pkg.source()
                                        .toAbsolutePath()
                                        .normalize()
                                        .startsWith(packageCatalog))
                .toList();
    }

    private List<LocalPackage> packages(Path workspace, boolean cloned) {
        try (WorkspaceStorage workspaceStorage =
                new WorkspaceStorage(workspace, workspaceConfig(workspace))) {
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

    public Path workspaceConfig(Path workspace) {
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

    private Path packageFolder(Path parent, String packageName) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path packageFolder = normalizedParent.resolve(packageName).normalize();
        if (!normalizedParent.equals(packageFolder.getParent())) {
            throw new IllegalArgumentException("Invalid package name: " + packageName);
        }
        return packageFolder;
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException error)
                            throws IOException {
                        if (error != null) {
                            throw error;
                        }
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
